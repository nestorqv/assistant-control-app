package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryPhotoAccessIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    private long clockIn(CheckInClient.Actor actor) throws Exception {
        JsonNode body = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        return body.get("id").asLong();
    }

    private String employeePath(long id) {
        return "/api/time-entries/" + id + "/photo";
    }

    private String adminPath(long id) {
        return "/api/admin/time-entries/" + id + "/photo";
    }

    @Test
    void anEmployeeReadsTheirOwnPhoto() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);

        MockHttpServletResponse response = client.get(actor, employeePath(id)).andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store").contains("private");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("inline");
        assertThat(response.getContentAsByteArray())
                .isEqualTo(Files.readAllBytes(client.storedFile(client.photoKey(id))));
    }

    @Test
    void anotherEmployeeOfTheSameCompanyGets404() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor owner = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor other = client.actor(tenant, "EMPLOYEE", true);
        long id = clockIn(owner);
        client.get(other, employeePath(id)).andExpect(status().isNotFound());
    }

    @Test
    void anotherCompanyGets404OnBothRoutes() throws Exception {
        CheckInClient.Actor owner = client.actor();
        CheckInClient.Actor outsider = client.actor();
        CheckInClient.Actor outsiderAdmin = client.actor(outsider.tenant(), "ADMIN", true);
        long id = clockIn(owner);
        client.get(outsider, employeePath(id)).andExpect(status().isNotFound());
        client.get(outsiderAdmin, adminPath(id)).andExpect(status().isNotFound());
    }

    @Test
    void anEntryWithoutAPhotoGets404() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = data.timeEntry(actor.companyId(), actor.employeeId(), actor.tenant().branchId());
        client.get(actor, employeePath(id)).andExpect(status().isNotFound());
    }

    @Test
    void theAdminOfTheSameCompanyReadsAnyPhoto() throws Exception {
        CheckInClient.Actor owner = client.actor();
        CheckInClient.Actor admin = client.actor(owner.tenant(), "ADMIN", true);
        long id = clockIn(owner);
        MockHttpServletResponse response = client.get(admin, adminPath(id)).andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("image/jpeg");
        assertThat(response.getContentAsByteArray().length).isPositive();
    }

    @Test
    void anEmployeeOnTheAdminRouteGets403AndNoTokenGets401() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);
        client.get(actor, adminPath(id)).andExpect(status().isForbidden());
        client.get(null, employeePath(id)).andExpect(status().isUnauthorized());
        client.get(null, adminPath(id)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonNumericIdIs400WithoutEchoOrAPath() throws Exception {
        CheckInClient.Actor actor = client.actor();
        MockHttpServletResponse response = client.get(actor, "/api/time-entries/..%2F..%2Fetc/photo")
                .andReturn().getResponse();
        assertThat(response.getStatus()).isIn(400, 404);
        assertThat(response.getContentAsString()).doesNotContain("etc");
        MockHttpServletResponse nonNumeric = client.get(actor, "/api/time-entries/abc/photo")
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(nonNumeric.getContentAsString()).doesNotContain("abc");
    }

    @Test
    void aFileDeletedFromDiskGets404() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);
        Files.delete(client.storedFile(client.photoKey(id)));
        client.get(actor, employeePath(id)).andExpect(status().isNotFound());
    }

    @Test
    void aNotFoundStaysNotFoundWhenTheClientAsksForAnImage() throws Exception {
        CheckInClient.Actor actor = client.actor();
        MockHttpServletResponse response = client.perform(get(employeePath(999_999_999L))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token())
                .accept(MediaType.IMAGE_JPEG)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
    }
}
