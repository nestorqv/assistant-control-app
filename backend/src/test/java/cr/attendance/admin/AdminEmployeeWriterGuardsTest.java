package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.Role;
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
import org.springframework.security.access.AccessDeniedException;

/**
 * The company must never be left without an active administrator. The actor is checked first (an already demoted
 * or deactivated admin gets 403 and learns nothing), a self change is refused, and the admin set is locked so
 * concurrent privilege writes serialize. LAST_ADMIN itself is a defensive answer behind those checks: with the
 * actor in the set and a different target, the set always has two members, so the tests assert the guarantee
 * (never zero active admins) rather than the code.
 */
@IntegrationTest
class AdminEmployeeWriterGuardsTest {

    private static final int ROUNDS = 15;

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminEmployeeWriter writer;

    private int activeAdmins(long companyId) {
        return jdbc.queryForObject("select count(*) from employee where company_id = ? and role = 'ADMIN' and active",
                Integer.class, companyId);
    }

    private int version(long id) {
        return jdbc.queryForObject("select version from employee where id = ?", Integer.class, id);
    }

    private Map<String, Object> demote(long id, long branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Admin");
        body.put("jobTitle", null);
        body.put("role", "EMPLOYEE");
        body.put("branchId", branchId);
        body.put("expectedVersion", version(id));
        return body;
    }

    private int statusOf(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getStatus();
    }

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

    @Test
    void theLastAdminGuardRefusesAnEmptyAdminSetAndAcceptsAnyLargerOne() throws Exception {
        // Unreachable through HTTP (the actor is always in the set and is not the target), so the guard is called directly.
        var guard = AdminEmployeeWriter.class.getDeclaredMethod("requireAnotherAdminRemains", List.class, boolean.class);
        guard.setAccessible(true);
        assertThatThrownBy(() -> guard.invoke(null, List.of(1L), true))
                .hasCauseInstanceOf(cr.attendance.common.ApiException.class)
                .satisfies(e -> assertThat(((cr.attendance.common.ApiException) e.getCause()).code())
                        .isEqualTo(cr.attendance.common.ErrorCode.LAST_ADMIN));
        guard.invoke(null, List.of(1L, 2L), true);
        guard.invoke(null, List.of(1L), false);
    }

    @Test
    void theSoleRemainingAdminCannotDemoteOrDeactivateThemselvesAndStaysActive() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin a = admins.admin(t);
        AdminClient.Admin b = admins.admin(t);
        // A deactivates the only other admin: allowed, since A remains.
        admins.post(a.token(), "/api/admin/employees/" + b.id() + "/deactivate", Map.of("expectedVersion", version(b.id())))
                .andExpect(status().isOk());
        assertThat(activeAdmins(t.companyId())).isEqualTo(1);

        // A is now the last admin: every way of removing A is refused.
        admins.put(a.token(), "/api/admin/employees/" + a.id(), demote(a.id(), t.branchId()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CHANGE_NOT_ALLOWED"));
        admins.post(a.token(), "/api/admin/employees/" + a.id() + "/deactivate", Map.of("expectedVersion", version(a.id())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELF_CHANGE_NOT_ALLOWED"));
        assertThat(activeAdmins(t.companyId())).isEqualTo(1);
    }

    @Test
    void anAdminAlreadyDemotedByAnotherCannotDemoteOrDeactivateTheOtherThroughTheWriter() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin a = admins.admin(t);
        AdminClient.Admin b = admins.admin(t);
        EmployeePrincipal bPrincipal = new EmployeePrincipal(b.id(), t.companyId(), Role.ADMIN);
        admins.put(a.token(), "/api/admin/employees/" + b.id(), demote(b.id(), t.branchId())).andExpect(status().isOk());

        // B's token would be refused at the filter; the principal below models a request already past it.
        var update = new ManagementRequests.EmployeeUpdate("Admin", null, Role.EMPLOYEE, t.branchId(), version(a.id()));
        assertThatThrownBy(() -> writer.update(bPrincipal, a.id(), update)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writer.deactivate(bPrincipal, a.id(), version(a.id()))).isInstanceOf(AccessDeniedException.class);
        assertThat(activeAdmins(t.companyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from employee where id = ?", String.class, a.id())).isEqualTo("ADMIN");
    }

    @Test
    void aDeactivatedActorEditingANameGetsAccessDeniedAndNothingChanges() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin a = admins.admin(t);
        admins.admin(t);
        long target = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String name = jdbc.queryForObject("select name from employee where id = ?", String.class, target);
        jdbc.update("update employee set active = false where id = ?", a.id());
        var update = new ManagementRequests.EmployeeUpdate("Nombre Nuevo", null, Role.EMPLOYEE, t.branchId(), version(target));

        assertThatThrownBy(() -> writer.update(new EmployeePrincipal(a.id(), t.companyId(), Role.ADMIN), target, update))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select name from employee where id = ?", String.class, target)).isEqualTo(name);
    }

    @Test
    void concurrentMutualDemotionNeverLeavesTheCompanyWithoutAnAdminAndNeverBothSucceed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestData.Tenant t = data.tenant();
            AdminClient.Admin a = admins.admin(t);
            AdminClient.Admin b = admins.admin(t);
            Map<String, Object> demoteB = demote(b.id(), t.branchId());
            Map<String, Object> demoteA = demote(a.id(), t.branchId());
            List<Integer> statuses = runTogether(List.of(
                    () -> statusOf(admins.put(a.token(), "/api/admin/employees/" + b.id(), demoteB)),
                    () -> statusOf(admins.put(b.token(), "/api/admin/employees/" + a.id(), demoteA))));
            assertThat(statuses).allMatch(s -> s == 200 || s == 401 || s == 403 || s == 409 || s == 503,
                    "statuses were " + statuses);
            assertThat(statuses.stream().filter(s -> s == 200).count()).isLessThanOrEqualTo(1);
            assertThat(activeAdmins(t.companyId())).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void concurrentDeactivationOfThreeAdminsInACycleLeavesAtLeastOneActive() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestData.Tenant t = data.tenant();
            AdminClient.Admin a = admins.admin(t);
            AdminClient.Admin b = admins.admin(t);
            AdminClient.Admin c = admins.admin(t);
            Map<String, Object> ofA = Map.of("expectedVersion", version(a.id()));
            Map<String, Object> ofB = Map.of("expectedVersion", version(b.id()));
            Map<String, Object> ofC = Map.of("expectedVersion", version(c.id()));
            List<Integer> statuses = runTogether(List.of(
                    () -> statusOf(admins.post(a.token(), "/api/admin/employees/" + b.id() + "/deactivate", ofB)),
                    () -> statusOf(admins.post(b.token(), "/api/admin/employees/" + c.id() + "/deactivate", ofC)),
                    () -> statusOf(admins.post(c.token(), "/api/admin/employees/" + a.id() + "/deactivate", ofA))));
            assertThat(statuses).allMatch(s -> s == 200 || s == 401 || s == 403 || s == 409 || s == 503,
                    "statuses were " + statuses);
            assertThat(activeAdmins(t.companyId())).isGreaterThanOrEqualTo(1);
        }
    }
}
