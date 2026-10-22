package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Test
    void v1AndV2AreAppliedSuccessfully() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null order by installed_rank",
                String.class);
        assertThat(versions).contains("1", "2");
    }

    @Test
    void sevenTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("company", "branch", "employee", "work_schedule", "time_entry", "evidence", "adjustment");
    }

    @Test
    void timeZoneAndActiveHaveDefaults() {
        long id = data.company();
        assertThat(jdbc.queryForObject("select time_zone from company where id = ?", String.class, id))
                .isEqualTo("America/Costa_Rica");
        assertThat(jdbc.queryForObject("select active from company where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void lowerCaseOrMalformedCodesAreRejected() {
        assertThatThrownBy(() -> data.company("demo-lowercase"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> data.company("AB"))
                .isInstanceOf(DataIntegrityViolationException.class);
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> jdbc.update("""
                insert into employee (company_id, branch_id, code, name, pin_hash, role)
                values (?, ?, 'abc', 'X', 'h', 'EMPLOYEE')
                """, t.companyId(), t.branchId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateCompanyCodeIsRejected() {
        String code = data.randomCode("D");
        data.company(code);
        assertThatThrownBy(() -> data.company(code)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEmployeeCodePerCompanyIsRejectedButAllowedAcrossCompanies() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String sql = """
                insert into employee (company_id, branch_id, code, name, pin_hash, role)
                values (?, ?, 'DUP1', 'X', 'h', 'EMPLOYEE')
                """;
        jdbc.update(sql, a.companyId(), a.branchId());
        jdbc.update(sql, b.companyId(), b.branchId());
        assertThatThrownBy(() -> jdbc.update(sql, a.companyId(), a.branchId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEnumValuesAndRangesAreRejected() {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> data.employee(t.companyId(), t.branchId(), "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");

        String timeEntrySql = """
                insert into time_entry (company_id, employee_id, branch_id, type, source, status, client_uuid)
                values (?, ?, ?, 'CLOCK_IN', ?, ?, ?)
                """;
        assertThatThrownBy(() -> jdbc.update(timeEntrySql, t.companyId(), employeeId, t.branchId(),
                "FAX", "VALID", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(timeEntrySql, t.companyId(), employeeId, t.branchId(),
                "MOBILE", "MAYBE", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        // PENDING_REVIEW has 14 characters: guards the varchar(20) widening of time_entry.status.
        jdbc.update(timeEntrySql, t.companyId(), employeeId, t.branchId(), "MOBILE", "PENDING_REVIEW",
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time)
                values (?, ?, 8, '08:00', '17:00')
                """, t.companyId(), employeeId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateClientUuidIsRejectedPerCompany() {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        UUID uuid = UUID.randomUUID();
        String sql = """
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid)
                values (?, ?, ?, 'CLOCK_IN', 'MOBILE', ?)
                """;
        jdbc.update(sql, t.companyId(), employeeId, t.branchId(), uuid);
        assertThatThrownBy(() -> jdbc.update(sql, t.companyId(), employeeId, t.branchId(), uuid))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The same uuid in another company is a different tenant's key, so it is allowed.
        TestData.Tenant other = data.tenant();
        long otherEmployee = data.employee(other.companyId(), other.branchId(), "EMPLOYEE");
        jdbc.update(sql, other.companyId(), otherEmployee, other.branchId(), uuid);
    }

    @Test
    void duplicateDayOfWeekPerEmployeeIsRejected() {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String sql = """
                insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time)
                values (?, ?, 1, '08:00', '17:00')
                """;
        jdbc.update(sql, t.companyId(), employeeId);
        assertThatThrownBy(() -> jdbc.update(sql, t.companyId(), employeeId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void employeeWithoutBranchIsRejectedUnlessAdmin() {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> data.employee(t.companyId(), null, "EMPLOYEE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(data.employee(t.companyId(), null, "ADMIN")).isPositive();
    }

    @Test
    void adjustmentAllowsNullOldValueButRequiresFieldAndNewValue() {
        TestData.Tenant t = data.tenant();
        long adminId = data.employee(t.companyId(), null, "ADMIN");
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long timeEntryId = data.timeEntry(t.companyId(), employeeId, t.branchId());
        jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, old_value, new_value)
                values (?, ?, ?, 'Olvido marcar', 'server_time', null, '2026-10-21T14:00:00Z')
                """, t.companyId(), timeEntryId, adminId);
        assertThatThrownBy(() -> jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, new_value)
                values (?, ?, ?, 'x', null, 'y')
                """, t.companyId(), timeEntryId, adminId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
