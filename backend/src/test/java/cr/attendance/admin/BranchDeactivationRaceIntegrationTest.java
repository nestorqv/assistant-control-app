package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deactivating a branch while an employee is assigned to it: one wins, the invariant always holds, never a 500. */
@IntegrationTest
class BranchDeactivationRaceIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void noActiveEmployeeIsEverLeftOnAnInactiveBranch() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin deactivator = admins.admin(t);
        AdminClient.Admin assigner = admins.admin(t);
        for (int round = 0; round < 20; round++) {
            long branch = data.branch(t.companyId());
            long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
            int version = jdbc.queryForObject("select version from employee where id = ?", Integer.class, employee);
            Map<String, Object> move = new HashMap<>();
            move.put("name", "Persona");
            move.put("jobTitle", null);
            move.put("role", "EMPLOYEE");
            move.put("branchId", branch);
            move.put("expectedVersion", version);
            List<Callable<Integer>> calls = List.of(
                    () -> admins.post(deactivator.token(), "/api/admin/branches/" + branch + "/deactivate", Map.of("expectedVersion", 0))
                            .andReturn().getResponse().getStatus(),
                    () -> admins.put(assigner.token(), "/api/admin/employees/" + employee, move).andReturn().getResponse().getStatus());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (Callable<Integer> call : calls) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return call.call();
                    }));
                }
                go.countDown();
                for (Future<Integer> f : futures) {
                    statuses.add(f.get(60, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
            // Exactly one of the two wins; the loser is a 409 (BRANCH_IN_USE or BRANCH_INACTIVE). A 503 would mean a
            // deadlock or lock timeout, i.e. a lock-order bug, so it is not accepted.
            assertThat(statuses).allMatch(s -> s == 200 || s == 409, "statuses were " + statuses);
            assertThat(statuses.stream().filter(s -> s == 200).count()).as("winners in " + statuses).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select count(*) from employee e join branch b on b.id = e.branch_id
                     where e.company_id = ? and e.active and not b.active
                    """, Integer.class, t.companyId())).isZero();
        }
    }
}
