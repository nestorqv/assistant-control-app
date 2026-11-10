package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.TestData;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** L-2: the entry detail carries GPS, so it is rate limited per admin and every location view is audited. */
@SpringBootTest(properties = "app.admin.rate-limit.detail.max-attempts=3")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AdminLocationViewIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private int views(long companyId) {
        return jdbc.queryForObject("select count(*) from audit_log where company_id = ? and action = 'LOCATION_VIEW'",
                Integer.class, companyId);
    }

    @Test
    void aDetailWithALocationWritesOneRowWithIdsOnlyAndLogsWithoutCoordinates(CapturedOutput output) throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        long entry = admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "VALID", null, 0);
        admins.get(admin.token(), "/api/admin/time-entries/" + entry).andExpect(status().isOk())
                .andExpect(jsonPath("$.location.latitude").exists());
        assertThat(views(t.companyId())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select actor_id, target_type, target_id, details from audit_log where action = 'LOCATION_VIEW' and company_id = ?",
                t.companyId());
        assertThat(row.get("actor_id")).isEqualTo(admin.id());
        assertThat(row.get("target_type")).isEqualTo("TIME_ENTRY");
        assertThat(row.get("target_id")).isEqualTo(entry);
        assertThat((String) row.get("details")).isEqualTo("entry=" + entry);
        assertThat(output.getAll()).contains("location_access actor=" + admin.id() + " entry=" + entry)
                .doesNotContain("9.9325").doesNotContain("-84.0796");
    }

    @Test
    void anEntryWithoutAnyLocationWritesNoRow() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        long entry = jdbc.queryForObject("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, status, client_uuid, work_date,
                                        server_time, effective_time)
                values (?, ?, ?, 'CLOCK_IN', 'ADMIN', 'VALID', ?, ?, now(), now()) returning id
                """, Long.class, t.companyId(), employee, t.branchId(), UUID.randomUUID(), LocalDate.now());
        admins.get(admin.token(), "/api/admin/time-entries/" + entry).andExpect(status().isOk())
                .andExpect(jsonPath("$.location").doesNotExist());
        assertThat(views(t.companyId())).isZero();
    }

    @Test
    void theLimiterCountsMissesTooAnswers429RateLimitedAndOtherAdminsAreUnaffected() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin first = admins.admin(t);
        AdminClient.Admin second = admins.admin(t);
        long employee = admins.employee(t);
        long entry = admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "VALID", null, 0);
        TestData.Tenant other = data.tenant();
        long foreign = admins.entry(other, admins.employee(other), "CLOCK_IN", LocalDate.now(), Instant.now(), "VALID", null, 0);

        admins.get(first.token(), "/api/admin/time-entries/" + entry).andExpect(status().isOk());
        admins.get(first.token(), "/api/admin/time-entries/987654").andExpect(status().isNotFound());
        admins.get(first.token(), "/api/admin/time-entries/" + foreign).andExpect(status().isNotFound());
        admins.get(first.token(), "/api/admin/time-entries/" + entry).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"));
        // One successful view, none for the misses or the refused attempt.
        assertThat(views(t.companyId())).isEqualTo(1);
        admins.get(second.token(), "/api/admin/time-entries/" + entry).andExpect(status().isOk());
        assertThat(views(t.companyId())).isEqualTo(2);
    }

    @Test
    void writingEndpointsDoNotWriteALocationViewRow() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        long entry = admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
        admins.post(admin.token(), "/api/admin/time-entries/" + entry + "/approve",
                Map.of("reason", "Verificado con la foto", "expectedStatus", "PENDING_REVIEW")).andExpect(status().isOk());
        assertThat(views(t.companyId())).isZero();
    }
}
