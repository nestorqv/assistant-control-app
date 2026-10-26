package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestImages;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryConcurrencyIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyThreadsPostingTheSameUuidCreateExactlyOneRowAndOneFile() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        byte[] photo = TestImages.jpeg();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> client.post(actor, form, photo).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(5);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void twoClockInsWithDifferentUuidsGiveOneCreatedAndOneConflict() throws Exception {
        CheckInClient.Actor actor = client.actor();
        byte[] photo = TestImages.jpeg();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            tasks.add(() -> client.post(actor, form, photo).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void theDatabaseRejectsASecondClockInForTheSameEmployeeAndWorkDate() throws Exception {
        CheckInClient.Actor actor = client.actor();
        String sql = """
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date)
                values (?, ?, ?, 'CLOCK_IN', 'MOBILE', ?, date '2026-10-22')
                """;
        jdbc.update(sql, actor.companyId(), actor.employeeId(), actor.tenant().branchId(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(sql, actor.companyId(), actor.employeeId(), actor.tenant().branchId(),
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_time_entry_employee_work_date_type");
    }

    @Test
    void aFailedInsertAfterTheFileWriteDeletesTheOrphanFileAndMapsTheBackstop() throws Exception {
        CheckInClient.Actor actor = client.actor();
        // A row for today's work date that is older than the look-back window: the sequence rules do not see
        // it, so the request reaches the insert, stores the file, and the unique index rejects the row.
        LocalDate today = clock.instant().atZone(CR).toLocalDate();
        jdbc.update("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date,
                                        server_time)
                values (?, ?, ?, 'CLOCK_IN', 'MOBILE', ?, ?, ?)
                """, actor.companyId(), actor.employeeId(), actor.tenant().branchId(), UUID.randomUUID(), today,
                Timestamp.from(clock.instant().minus(Duration.ofDays(10))));

        String code = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isConflict())).get("code").asText();

        assertThat(code).isEqualTo("ALREADY_CLOCKED_IN");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).isEmpty();
    }
}
