package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

/** Tester additions: response headers, no enumeration between "missing" and "not yours", retention, odd ids. */
@IntegrationTest
@Import(MutableClockConfig.class)
class PhotoAccessHardeningIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    private long post(CheckInClient.Actor actor, String type) throws Exception {
        JsonNode body = client.json(client.post(actor, client.form(type, clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        return body.get("id").asLong();
    }

    @Test
    void bothRoutesSendTheSafeImageHeaders() throws Exception {
        CheckInClient.Actor owner = client.actor();
        CheckInClient.Actor admin = client.actor(owner.tenant(), "ADMIN", true);
        long id = post(owner, "CLOCK_IN");
        long fileLength = Files.size(client.storedFile(client.photoKey(id)));

        for (MockHttpServletResponse r : new MockHttpServletResponse[] {
            client.get(owner, "/api/time-entries/" + id + "/photo").andExpect(status().isOk()).andReturn().getResponse(),
            client.get(admin, "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk()).andReturn()
                    .getResponse()}) {
            assertThat(r.getContentType()).isEqualTo("image/jpeg");
            assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store").contains("private")
                    .doesNotContain("public");
            assertThat(r.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline").contains("photo.jpg");
            assertThat(r.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(fileLength));
            byte[] body = r.getContentAsByteArray();
            assertThat(body[0]).isEqualTo((byte) 0xFF);
            assertThat(body[1]).isEqualTo((byte) 0xD8);
        }
    }

    @Test
    void theClockOutPhotoIsServedToo() throws Exception {
        CheckInClient.Actor owner = client.actor();
        post(owner, "CLOCK_IN");
        clock.advance(Duration.ofHours(8));
        long out = post(owner, "CLOCK_OUT");
        client.get(owner, "/api/time-entries/" + out + "/photo").andExpect(status().isOk());
    }

    @Test
    void anAdminOnTheEmployeeRouteSeesOnlyTheirOwnPhotos() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor employee = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor admin = client.adminWithBranch(tenant);
        long employeesPhoto = post(employee, "CLOCK_IN");
        long adminsPhoto = post(admin, "CLOCK_IN");

        client.get(admin, "/api/time-entries/" + employeesPhoto + "/photo").andExpect(status().isNotFound());
        client.get(admin, "/api/time-entries/" + adminsPhoto + "/photo").andExpect(status().isOk());
        client.get(admin, "/api/admin/time-entries/" + employeesPhoto + "/photo").andExpect(status().isOk());
    }

    @Test
    void everyKindOfMissAnswersWithTheSameBodyAndHeaders() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor owner = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor sibling = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor stranger = client.actor();
        CheckInClient.Actor strangerAdmin = client.actor(stranger.tenant(), "ADMIN", true);
        long id = post(owner, "CLOCK_IN");
        CheckInClient.Actor bare = client.actor(tenant, "EMPLOYEE", true);
        long noPhoto = data.timeEntry(bare.companyId(), bare.employeeId(), tenant.branchId());
        long purged = post(sibling, "CLOCK_IN");
        jdbc.update("update evidence set storage_key = null where time_entry_id = ? and type = 'PHOTO'", purged);

        Set<String> bodies = new HashSet<>();
        Set<String> contentTypes = new HashSet<>();
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {
            client.get(sibling, "/api/time-entries/" + id + "/photo").andReturn().getResponse(),       // not yours
            client.get(stranger, "/api/time-entries/" + id + "/photo").andReturn().getResponse(),      // other tenant
            client.get(strangerAdmin, "/api/admin/time-entries/" + id + "/photo").andReturn().getResponse(),
            client.get(owner, "/api/time-entries/999999999/photo").andReturn().getResponse(),          // missing
            client.get(bare, "/api/time-entries/" + noPhoto + "/photo").andReturn().getResponse(),  // no evidence
            client.get(sibling, "/api/time-entries/" + purged + "/photo").andReturn().getResponse()}) { // key purged
            assertThat(r.getStatus()).isEqualTo(404);
            bodies.add(r.getContentAsString());
            contentTypes.add(String.valueOf(r.getContentType()));
        }
        assertThat(bodies).hasSize(1);
        assertThat(contentTypes).hasSize(1);
        assertThat(bodies.iterator().next()).doesNotContain(String.valueOf(id)).contains("NOT_FOUND");
    }

    @Test
    void oddIdsNeverCauseAServerErrorOrEchoTheInput() throws Exception {
        CheckInClient.Actor actor = client.actor();
        for (String id : new String[] {"0", "-1", "99999999999999999999", "1.5", "%20", "1;2"}) {
            MockHttpServletResponse r = client.get(actor, "/api/time-entries/" + id + "/photo").andReturn()
                    .getResponse();
            assertThat(r.getStatus()).as(id).isIn(400, 404);
            assertThat(r.getContentAsString()).as(id).doesNotContain("99999999999999999999").doesNotContain("1.5");
        }
    }

    @Test
    void theSamePhotoUrlWithOtherMethodsIsNotServed() throws Exception {
        CheckInClient.Actor owner = client.actor();
        long id = post(owner, "CLOCK_IN");
        for (var builder : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/time-entries/" + id + "/photo"),
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/time-entries/" + id + "/photo")}) {
            MockHttpServletResponse r = client.perform(builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                    .andReturn().getResponse();
            assertThat(r.getStatus()).isIn(403, 405);
        }
        assertThat(Files.exists(client.storedFile(client.photoKey(id)))).isTrue();
    }
}
