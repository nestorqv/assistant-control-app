package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.support.AdminClient;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.TestImages;
import cr.attendance.support.TestData;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The shared lock protocol (admin set, actor KEY SHARE + target FOR UPDATE in id order, branch, entry): admins acting
 * on each other, or on a branch they sit in, never produce a 500 and never a deadlock: with the locks taken in the
 * documented order every answer is a real outcome (200/201, 409, or 401 after a PIN reset ended the caller's
 * sessions). 503 SERVER_BUSY is NOT accepted in the concurrent rounds: a reversed lock order shows up as a
 * deadlock or a lock timeout, which the service maps to 503.
 */
@SpringBootTest(properties = "app.time-entry.lock-timeout=3s")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles("test")
class ManagementLockOrderIntegrationTest {

    private static final int ROUNDS = 20;

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private CheckInClient checkIn;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private List<Integer> runTogether(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private int status(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getStatus();
    }

    private void assertNever500(List<Integer> statuses) {
        assertThat(statuses).allMatch(s -> s == 200 || s == 201 || s == 409, "statuses were " + statuses);
    }

    @Test
    void deactivatingABranchWhileAnotherAdminAssignsTheActorToItKeepsTheInvariant() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin a = admins.admin(t);
        AdminClient.Admin b = admins.admin(t);
        for (int round = 0; round < ROUNDS; round++) {
            long branch = data.branch(t.companyId());
            jdbc.update("update employee set branch_id = null where id = ?", a.id());
            Map<String, Object> assign = new HashMap<>();
            assign.put("name", "Admin A");
            assign.put("jobTitle", null);
            assign.put("role", "ADMIN");
            assign.put("branchId", branch);
            assign.put("expectedVersion", jdbc.queryForObject("select version from employee where id = ?", Integer.class, a.id()));
            List<Integer> statuses = runTogether(List.of(
                    () -> status(admins.post(a.token(), "/api/admin/branches/" + branch + "/deactivate", Map.of("expectedVersion", 0))),
                    () -> status(admins.put(b.token(), "/api/admin/employees/" + a.id(), assign))));
            assertNever500(statuses);
            // No active employee is ever left on an inactive branch.
            assertThat(jdbc.queryForObject("""
                    select count(*) from employee e join branch b on b.id = e.branch_id
                     where e.company_id = ? and e.active and not b.active
                    """, Integer.class, t.companyId())).isZero();
        }
    }

    @Test
    void adminsUnlockingAndEditingEachOthersScheduleAtTheSameTimeNeverGet500() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin a = admins.admin(t);
        AdminClient.Admin b = admins.admin(t);
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update employee set locked_until = now() + interval '10 minutes', failed_attempts = 2 where id in (?, ?)", a.id(), b.id());
            Map<String, Object> week = Map.of("expected", List.of(), "days", List.of());
            List<Integer> statuses = runTogether(List.of(
                    () -> status(admins.postEmpty(a.token(), "/api/admin/employees/" + b.id() + "/unlock")),
                    () -> status(admins.postEmpty(b.token(), "/api/admin/employees/" + a.id() + "/unlock")),
                    () -> status(admins.put(a.token(), "/api/admin/employees/" + b.id() + "/schedule", week)),
                    () -> status(admins.put(b.token(), "/api/admin/employees/" + a.id() + "/schedule", week))));
            assertNever500(statuses);
        }
    }

    @Test
    void adminsApprovingEachOthersEntriesAtTheSameTimeNeverGet500() throws Exception {
        TestData.Tenant t = data.tenant();
        for (int round = 0; round < 6; round++) {
            // Admins with a branch, so each has entries of their own to approve.
            long idA = data.employee(t.companyId(), t.branchId(), "ADMIN");
            long idB = data.employee(t.companyId(), t.branchId(), "ADMIN");
            String tokenA = admins.tokenFor(t, new TestData.TestUser(idA, code(idA), TestData.PIN));
            String tokenB = admins.tokenFor(t, new TestData.TestUser(idB, code(idB), TestData.PIN));
            long entryOfB = admins.entry(t, idB, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
            long entryOfA = admins.entry(t, idA, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
            Map<String, Object> body = Map.of("reason", "Verificado con la foto", "expectedStatus", "PENDING_REVIEW");
            List<Integer> statuses = runTogether(List.of(
                    () -> status(admins.post(tokenA, "/api/admin/time-entries/" + entryOfB + "/approve", body)),
                    () -> status(admins.post(tokenB, "/api/admin/time-entries/" + entryOfA + "/approve", body))));
            assertNever500(statuses);
        }
    }

    @Test
    void anAdminsOwnClockInAndThatAdminEditingTheirBranchNeverDeadlock() throws Exception {
        TestData.Tenant t = data.tenant();
        for (int round = 0; round < 10; round++) {
            // A new admin who sits in the branch clocks in while the same admin edits that branch.
            CheckInClient.Actor sitting = checkIn.adminWithBranch(t);
            Map<String, Object> edit = new HashMap<>();
            edit.put("name", "Sucursal " + round + " " + System.nanoTime());
            edit.put("latitude", 9.9325);
            edit.put("longitude", -84.0796);
            edit.put("radiusMeters", 150 + round);
            edit.put("expectedVersion", jdbc.queryForObject("select version from branch where id = ?", Integer.class, t.branchId()));
            Map<String, String> form = checkIn.form("CLOCK_IN", Instant.now());
            List<Integer> statuses = runTogether(List.of(
                    () -> status(checkIn.post(sitting, form, TestImages.jpeg())),
                    () -> status(admins.put(sitting.token(), "/api/admin/branches/" + t.branchId(), edit))));
            assertNever500(statuses);
        }
    }

    @Test
    void adminsResettingEachOthersLockedPinAtTheSameTimeNeverDeadlock() throws Exception {
        TestData.Tenant t = data.tenant();
        for (int round = 0; round < ROUNDS; round++) {
            // A locked admin may have the PIN reset by another admin; the reset also ends the target's sessions (401).
            long idA = data.employee(t.companyId(), null, "ADMIN");
            long idB = data.employee(t.companyId(), null, "ADMIN");
            String tokenA = admins.tokenFor(t, new TestData.TestUser(idA, code(idA), TestData.PIN));
            String tokenB = admins.tokenFor(t, new TestData.TestUser(idB, code(idB), TestData.PIN));
            jdbc.update("update employee set locked_until = now() + interval '10 minutes' where id in (?, ?)", idA, idB);
            List<Integer> statuses = runTogether(List.of(
                    () -> status(admins.postEmpty(tokenA, "/api/admin/employees/" + idB + "/pin-reset")),
                    () -> status(admins.postEmpty(tokenB, "/api/admin/employees/" + idA + "/pin-reset"))));
            assertThat(statuses).allMatch(s -> s == 200 || s == 401 || s == 409, "statuses were " + statuses);
        }
    }

    private String code(long employeeId) {
        return jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
    }

    @Test
    void aRealDeadlockEndsAsServerBusyWithRetryAfterNeverA500() throws Exception {
        TestData.Tenant t = data.tenant();
        // The target has the lower id, so the unlock locks it (FOR UPDATE) before the actor (FOR KEY SHARE).
        long target = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        AdminClient.Admin actor = admins.admin(t);
        assertThat(target).isLessThan(actor.id());
        jdbc.update("update employee set locked_until = now() + interval '10 minutes', failed_attempts = 2 where id = ?", target);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<ResultActions> request;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                // An outside transaction holds the actor's row FOR UPDATE: the request waits for it after locking the target.
                s.execute("select 1 from employee where id = " + actor.id() + " for update");
                request = pool.submit(() -> admins.postEmpty(actor.token(), "/api/admin/employees/" + target + "/unlock"));
                // Wait until the request is really blocked on the actor's row (a lock not granted), so it already holds the target.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (jdbc.queryForObject("select count(*) from pg_locks where not granted and locktype in ('transactionid', 'tuple')", Integer.class) == 0) {
                    assertThat(System.nanoTime()).as("the request is blocked on the actor row").isLessThan(deadline);
                    Thread.sleep(25);
                }
                try {
                    // ...and now asks for the row the request holds: a cycle, ended by PostgreSQL (SQLSTATE 40P01).
                    s.execute("select 1 from employee where id = " + target + " for update");
                } catch (SQLException victim) {
                    assertThat(victim.getSQLState()).isEqualTo("40P01");
                }
            } finally {
                c.rollback();
            }
        } finally {
            pool.shutdown();
        }
        // The request locked the target first and has waited longest, so PostgreSQL picks it as the victim: it must
        // answer 503 with Retry-After and the fixed SERVER_BUSY text, never a 500 and not a silent success.
        var response = request.get(30, TimeUnit.SECONDS).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).contains("SERVER_BUSY");
    }
}
