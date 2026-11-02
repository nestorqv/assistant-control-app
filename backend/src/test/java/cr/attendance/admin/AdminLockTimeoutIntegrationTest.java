package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** A review action that cannot get the employee row lock in time answers 503 SERVER_BUSY and changes nothing. */
@IntegrationTest
@TestPropertySource(properties = "app.time-entry.lock-timeout=700ms")
class AdminLockTimeoutIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private DataSource dataSource;

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void approveWhileTheEmployeeRowIsHeldGives503ServerBusyAndLeavesNoAdjustment() throws Exception {
        TestData.Tenant tenant = data.tenant();
        AdminClient.Admin admin = admins.admin(tenant);
        long employee = admins.employee(tenant);
        LocalDate day = LocalDate.now(ZoneId.of("America/Costa_Rica"));
        long id = admins.entry(tenant, employee, "CLOCK_IN", day, Instant.now(), "PENDING_REVIEW", "NO_GPS", 0);
        Map<String, String> body = Map.of("reason", "Verificado con la foto", "expectedStatus", "PENDING_REVIEW");

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from employee where id = ? for update")) {
                lock.setLong(1, employee);
                lock.executeQuery();

                long start = System.nanoTime();
                var result = admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve", body)
                        .andExpect(status().isServiceUnavailable());
                Duration waited = Duration.ofNanos(System.nanoTime() - start);

                JsonNode error = admins.json(result);
                assertThat(error.get("code").asText()).isEqualTo("SERVER_BUSY");
                assertThat(waited).isLessThan(Duration.ofSeconds(5));
                assertThat(admins.adjustmentCount(id)).isZero();
            } finally {
                holder.rollback();
            }
        }
        // The lock is gone: the same action now works, so no connection was left stuck.
        admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve", body).andExpect(status().isOk());
    }
}
