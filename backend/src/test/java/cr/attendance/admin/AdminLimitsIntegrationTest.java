package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Own context: small limits so the caps can be reached with a handful of requests. */
@SpringBootTest(properties = {"app.admin.max-dashboard-rows=2", "app.admin.rate-limit.photo.max-attempts=3"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles("test")
@Import(MutableClockConfig.class)
class AdminLimitsIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private AdminClient admins;

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

    @Test
    void moreRowsThanTheCapIsReportTooLarge() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        admins.employee(t);
        admins.employee(t);
        admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk());
        admins.employee(t);
        String content = admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(content).contains("REPORT_TOO_LARGE");
    }

    @Test
    void thePhotoLimiterCountsEveryAttemptPerAdminAndOnlyAuditsSuccessfulViews() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated())).get("id").asLong();
        AdminClient.Admin first = admins.admin(actor.tenant());
        AdminClient.Admin second = admins.admin(actor.tenant());

        admins.get(first.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk());
        // Misses count too: the limiter runs before the lookup.
        admins.get(first.token(), "/api/admin/time-entries/987654/photo").andExpect(status().isNotFound());
        admins.get(first.token(), "/api/admin/time-entries/987655/photo").andExpect(status().isNotFound());
        admins.get(first.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"));

        // Another admin has their own budget.
        admins.get(second.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where company_id = ? and action = 'PHOTO_VIEW'",
                Integer.class, actor.companyId())).isEqualTo(2);
    }
}
