package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tester additions: races the employee row lock does NOT cover (two employees) and mixed replay traffic. */
@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryRaceConditionsIntegrationTest {

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

    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
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
    void twoEmployeesRacingForTheSameUuidGiveOneEntryOneFileAndAConflict() throws Exception {
        // The row lock is per employee, so these two requests are NOT serialized: the unique index decides.
        for (int round = 0; round < 5; round++) {
            TestData.Tenant tenant = data.tenant();
            CheckInClient.Actor first = client.actor(tenant, "EMPLOYEE", true);
            CheckInClient.Actor second = client.actor(tenant, "EMPLOYEE", true);
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            byte[] photo = TestImages.jpeg();

            List<Integer> statuses = runConcurrently(List.of(
                    () -> client.post(first, form, photo).andReturn().getResponse().getStatus(),
                    () -> client.post(second, form, photo).andReturn().getResponse().getStatus()));

            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("select count(*) from time_entry where company_id = ? and client_uuid = ?::uuid",
                    Integer.class, tenant.companyId(), form.get("clientUuid"))).isEqualTo(1);
            assertThat(client.filesOf(tenant.companyId())).as("loser's file must be deleted on rollback").hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from evidence where company_id = ?", Integer.class,
                    tenant.companyId())).isEqualTo(2); // PHOTO + GPS of the winner only
        }
    }

    @Test
    void twoClockOutsRacingAfterAClockInGiveOneCreatedAndOneNotClockedIn() throws Exception {
        CheckInClient.Actor actor = client.actor();
        client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(8));
        byte[] photo = TestImages.jpeg();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, String> form = client.form("CLOCK_OUT", clock.instant());
            tasks.add(() -> client.post(actor, form, photo).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(3);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(2);
        assertThat(client.filesOf(actor.companyId())).hasSize(2);
    }

    @Test
    void replaysWithAndWithoutAPhotoRacingTheOriginalAllAgree() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        byte[] photo = TestImages.jpeg();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            byte[] sent = i % 2 == 0 ? photo : null;
            tasks.add(() -> client.post(actor, form, sent).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(tasks);

        // Photo-less callers that lose the race to the first writer get 200; those that run before any row
        // exists are told the photo is required. Never a 5xx, never more than one row or file.
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 400);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }
}
