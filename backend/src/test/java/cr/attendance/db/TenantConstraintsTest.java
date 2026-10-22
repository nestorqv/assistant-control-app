package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TenantConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Test
    void employeeCannotUseBranchOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        assertThatThrownBy(() -> data.employee(a.companyId(), b.branchId(), "EMPLOYEE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void workScheduleCannotUseEmployeeOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long employeeB = data.employee(b.companyId(), b.branchId(), "EMPLOYEE");
        assertThatThrownBy(() -> jdbc.update("""
                insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time)
                values (?, ?, 1, '08:00', '17:00')
                """, a.companyId(), employeeB)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void timeEntryCannotUseEmployeeOrBranchOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long employeeA = data.employee(a.companyId(), a.branchId(), "EMPLOYEE");
        long employeeB = data.employee(b.companyId(), b.branchId(), "EMPLOYEE");
        String sql = """
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid)
                values (?, ?, ?, 'CLOCK_IN', 'MOBILE', ?)
                """;
        assertThatThrownBy(() -> jdbc.update(sql, a.companyId(), employeeB, a.branchId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, a.companyId(), employeeA, b.branchId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evidenceAndAdjustmentCannotUseTimeEntryOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long adminA = data.employee(a.companyId(), null, "ADMIN");
        long employeeB = data.employee(b.companyId(), b.branchId(), "EMPLOYEE");
        long timeEntryB = data.timeEntry(b.companyId(), employeeB, b.branchId());

        assertThatThrownBy(() -> jdbc.update(
                "insert into evidence (company_id, time_entry_id, type) values (?, ?, 'GPS')", a.companyId(), timeEntryB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, new_value)
                values (?, ?, ?, 'm', 'status', 'VALID')
                """, a.companyId(), timeEntryB, adminA)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void adjustmentCannotUseAdminOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long employeeA = data.employee(a.companyId(), a.branchId(), "EMPLOYEE");
        long timeEntryA = data.timeEntry(a.companyId(), employeeA, a.branchId());
        long adminB = data.employee(b.companyId(), null, "ADMIN");
        assertThatThrownBy(() -> jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, new_value)
                values (?, ?, ?, 'm', 'status', 'VALID')
                """, a.companyId(), timeEntryA, adminB)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAParentWithChildrenIsRestricted() {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        data.timeEntry(t.companyId(), employeeId, t.branchId());
        assertThatThrownBy(() -> jdbc.update("delete from company where id = ?", t.companyId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from employee where id = ?", employeeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
