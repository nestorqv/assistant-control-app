package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L-1 for the 4a review reasons: invisible characters are refused, accented Spanish passes, nothing is echoed. */
@IntegrationTest
class ReviewRequestsTextIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reasonsWithBidiOrZeroWidthCharactersAreRefusedWithoutEchoAndWriteNothing() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        long entry = admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
        String path = "/api/admin/time-entries/" + entry;
        for (String reason : new String[] {"Verificado\u202e con foto", "Verificado\u200b con foto", "Verificado\ufeff con foto", "Verificado\u2028 con foto"}) {
            String response = admins.post(admin.token(), path + "/approve", Map.of("reason", reason, "expectedStatus", "PENDING_REVIEW"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("Verificado");
            admins.post(admin.token(), path + "/reject", Map.of("reason", reason, "expectedStatus", "PENDING_REVIEW")).andExpect(status().isBadRequest());
            admins.post(admin.token(), path + "/correct", Map.of("reason", reason, "expectedStatus", "PENDING_REVIEW",
                    "expectedEffectiveTime", Instant.now().toString(), "localTime", LocalDate.now() + "T08:00"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            String created = admins.post(admin.token(), "/api/admin/time-entries", Map.of("employeeId", employee, "type", "CLOCK_OUT",
                    "workDate", LocalDate.now().toString(), "localTime", LocalDate.now() + "T17:00", "reason", reason))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(created).doesNotContain("Verificado");
        }
        assertThat(admins.adjustmentCount(entry)).isZero();
        admins.post(admin.token(), path + "/approve", Map.of("reason", "Verificado con la fotografía, José Ñandú", "expectedStatus", "PENDING_REVIEW"))
                .andExpect(status().isOk());
        assertThat(admins.adjustmentCount(entry)).isEqualTo(1);
    }

    @Test
    void aDecomposedAccentIsStoredComposed() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        long entry = admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
        // "o" followed by U+0301 (combining acute accent) must be stored as the single character U+00F3.
        admins.post(admin.token(), "/api/admin/time-entries/" + entry + "/approve",
                Map.of("reason", "Verificacio\u0301n completa", "expectedStatus", "PENDING_REVIEW")).andExpect(status().isOk());
        String stored = jdbc.queryForObject("select reason from adjustment where time_entry_id = ? and action = 'APPROVE'", String.class, entry);
        assertThat(stored).isEqualTo("Verificaci\u00f3n completa");
    }
}
