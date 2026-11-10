package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.Role;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The actor is re-read under the lock in every writer. The principal here still says ADMIN (as a token issued a
 * moment ago would), but the row was demoted or deactivated: the answer is an empty 403 and nothing is written.
 */
@IntegrationTest
@Import(ActorRecheckIntegrationTest.HookedEncoder.class)
class ActorRecheckIntegrationTest {

    /** Runs a hook before hashing, to model a demotion that commits while the PIN is being hashed. */
    static final AtomicReference<Runnable> BEFORE_ENCODE = new AtomicReference<>();

    @TestConfiguration
    static class HookedEncoder {
        @Bean
        @Primary
        PasswordEncoder hookedPasswordEncoder() {
            BCryptPasswordEncoder delegate = new BCryptPasswordEncoder(4);
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence raw) {
                    Runnable hook = BEFORE_ENCODE.get();
                    if (hook != null) {
                        hook.run();
                    }
                    return delegate.encode(raw);
                }

                @Override
                public boolean matches(CharSequence raw, String encoded) {
                    return delegate.matches(raw, encoded);
                }
            };
        }
    }

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminEmployeeWriter employeeWriter;

    @Autowired
    private AdminEmployeeService employeeService;

    @Autowired
    private AdminBranchWriter branchWriter;

    @Autowired
    private AdminScheduleWriter scheduleWriter;

    @Autowired
    private ReviewWriter reviewWriter;

    @AfterEach
    void clearHook() {
        BEFORE_ENCODE.set(null);
    }

    private int auditRows(long companyId) {
        return jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class, companyId);
    }

    private EmployeePrincipal demoted(TestData.Tenant t, boolean deactivate) {
        long admin = data.employee(t.companyId(), null, "ADMIN");
        EmployeePrincipal principal = new EmployeePrincipal(admin, t.companyId(), Role.ADMIN);
        if (deactivate) {
            jdbc.update("update employee set active = false where id = ?", admin);
        } else {
            jdbc.update("update employee set role = 'EMPLOYEE', branch_id = ? where id = ?", t.branchId(), admin);
        }
        return principal;
    }

    @Test
    void everyWriterRefusesADemotedOrDeactivatedActorAndWritesNothing() {
        for (boolean deactivate : new boolean[] {false, true}) {
            TestData.Tenant t = data.tenant();
            EmployeePrincipal actor = demoted(t, deactivate);
            long target = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
            long entry = admins.entry(t, target, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
            int branches = jdbc.queryForObject("select count(*) from branch where company_id = ?", Integer.class, t.companyId());
            int employees = jdbc.queryForObject("select count(*) from employee where company_id = ?", Integer.class, t.companyId());
            String hash = jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target);

            var create = new ManagementRequests.EmployeeCreate("NUEVO1", "Nombre", null, Role.EMPLOYEE, t.branchId());
            var update = new ManagementRequests.EmployeeUpdate("Otro", null, Role.EMPLOYEE, t.branchId(), 0);
            var branchCreate = new ManagementRequests.BranchCreate("Nueva", 9.9, -84.0, 100);
            var branchUpdate = new ManagementRequests.BranchUpdate("Renombrada", 9.9, -84.0, 100, 0);
            var schedule = new ManagementRequests.ScheduleReplace(List.of(),
                    List.of(new ManagementRequests.ScheduleDay(1, "08:00", "17:00", 5)));
            var approve = new ReviewRequests.Action("Verificado con la foto", cr.attendance.timeentry.TimeEntryStatus.PENDING_REVIEW);

            assertThatThrownBy(() -> employeeWriter.create(actor, create, "hash")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> employeeWriter.update(actor, target, update)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> employeeWriter.deactivate(actor, target, 0)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> employeeWriter.activate(actor, target, 0)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> employeeWriter.resetPin(actor, target, "hash", null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> employeeWriter.unlock(actor, target)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> scheduleWriter.replace(actor, target, schedule)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> branchWriter.create(actor, branchCreate)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> branchWriter.update(actor, t.branchId(), branchUpdate)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> branchWriter.activate(actor, t.branchId(), 0)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> branchWriter.deactivate(actor, t.branchId(), 0)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reviewWriter.approve(actor, entry, approve)).isInstanceOf(AccessDeniedException.class);

            assertThat(jdbc.queryForObject("select count(*) from branch where company_id = ?", Integer.class, t.companyId())).isEqualTo(branches);
            assertThat(jdbc.queryForObject("select count(*) from employee where company_id = ?", Integer.class, t.companyId())).isEqualTo(employees);
            assertThat(jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target)).isEqualTo(hash);
            assertThat(auditRows(t.companyId())).isZero();
            assertThat(admins.adjustmentCount(entry)).isZero();
            assertThat(jdbc.queryForObject("select status from time_entry where id = ?", String.class, entry)).isEqualTo("PENDING_REVIEW");
        }
    }

    @Test
    void aDemotionThatCommitsWhileThePinIsBeingHashedStillGives403AndNoReset() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        EmployeePrincipal actor = new EmployeePrincipal(admin, t.companyId(), Role.ADMIN);
        long target = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String hash = jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target);

        BEFORE_ENCODE.set(() -> jdbc.update("update employee set role = 'EMPLOYEE', branch_id = ? where id = ?", t.branchId(), admin));
        assertThatThrownBy(() -> employeeService.resetPin(actor, target, null)).isInstanceOf(AccessDeniedException.class);
        BEFORE_ENCODE.set(null);

        assertThat(jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target)).isEqualTo(hash);
        assertThat(auditRows(t.companyId())).isZero();
    }

    @Test
    void aDemotedAdminResettingAMissingEmployeeGets403NotNotFound() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        EmployeePrincipal actor = new EmployeePrincipal(admin, t.companyId(), Role.ADMIN);
        long missing = Integer.MAX_VALUE;
        jdbc.update("update employee set role = 'EMPLOYEE', branch_id = ? where id = ?", t.branchId(), admin);

        assertThatThrownBy(() -> employeeService.resetPin(actor, missing, null)).isInstanceOf(AccessDeniedException.class);
        assertThat(auditRows(t.companyId())).isZero();
    }
}
