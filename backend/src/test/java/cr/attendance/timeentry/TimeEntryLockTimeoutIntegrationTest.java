package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestImages;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Audit L-3: a held employee row lock makes the POST fail fast with 503 instead of hanging. */
@IntegrationTest
@TestPropertySource(properties = "app.time-entry.lock-timeout=700ms")
class TimeEntryLockTimeoutIntegrationTest {

    @Autowired
    private CheckInClient client;

    @Autowired
    private DataSource dataSource;

    @Test
    void aHeldRowLockGives503ServerBusyWithinTheTimeoutAndNothingIsWritten() throws Exception {
        CheckInClient.Actor actor = client.actor();
        // Company ids restart in every embedded database: compare with what is already on disk.
        int filesBefore = client.filesOf(actor.companyId()).size();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("select id from employee where id = ? for update")) {
                lock.setLong(1, actor.employeeId());
                lock.executeQuery();

                long start = System.nanoTime();
                var result = client.post(actor, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg())
                        .andExpect(status().isServiceUnavailable());
                Duration waited = Duration.ofNanos(System.nanoTime() - start);

                JsonNode body = client.json(result);
                assertThat(body.get("code").asText()).isEqualTo("SERVER_BUSY");
                assertThat(result.andReturn().getResponse().getHeader("Retry-After")).isEqualTo("5");
                assertThat(waited).isLessThan(Duration.ofSeconds(5));
                assertThat(client.entryCount(actor.employeeId())).isZero();
                assertThat(client.filesOf(actor.companyId())).hasSize(filesBefore);
            } finally {
                holder.rollback();
            }
        }
        // The lock is gone: the same employee can clock in, and the pool was not left with a stuck connection.
        client.post(actor, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg()).andExpect(status().isCreated());
    }
}
