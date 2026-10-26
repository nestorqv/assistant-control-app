package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** CHECK / NOT NULL / unique constraints of V2 that the main schema tests do not touch. */
@IntegrationTest
class SchemaRangeConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private void assertRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void branchCoordinatesRadiusAndNameAreChecked() {
        long company = data.company();
        String sql = "insert into branch (company_id, name, latitude, longitude, radius_meters) values (?, ?, ?, ?, ?)";
        jdbc.update(sql, company, "ok", 90, 180, 10);
        jdbc.update(sql, company, "ok2", -90, -180, 5000);
        assertRejected(() -> jdbc.update(sql, company, "lat+", 90.0001, 0, 100));
        assertRejected(() -> jdbc.update(sql, company, "lat-", -90.0001, 0, 100));
        assertRejected(() -> jdbc.update(sql, company, "lon+", 0, 180.0001, 100));
        assertRejected(() -> jdbc.update(sql, company, "lon-", 0, -180.0001, 100));
        assertRejected(() -> jdbc.update(sql, company, "radio-", 0, 0, 9));
        assertRejected(() -> jdbc.update(sql, company, "radio+", 0, 0, 5001));
        assertRejected(() -> jdbc.update(sql, company, "ok", 1, 1, 100));
    }

    @Test
    void sameBranchNameIsAllowedInDifferentCompanies() {
        long a = data.company();
        long b = data.company();
        String sql = "insert into branch (company_id, name, latitude, longitude) values (?, 'Centro', 9, -84)";
        jdbc.update(sql, a);
        jdbc.update(sql, b);
    }

    @Test
    void companyNameCannotBeBlankAndCodeLengthIsChecked() {
        assertRejected(() -> jdbc.update("insert into company (code, name) values (?, '   ')", data.randomCode("N")));
        assertRejected(() -> data.company("A".repeat(21)));
        assertRejected(() -> data.company("CON ESPACIO"));
        assertRejected(() -> data.company("CON_GUION_BAJO"));
        assertThat(data.company("ABC")).isPositive();
        assertThat(data.company("A-B-" + "1".repeat(16))).isPositive();
    }

    @Test
    void employeeCounterAndPinHashConstraints() {
        TestData.Tenant t = data.tenant();
        long id = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        assertRejected(() -> jdbc.update("update employee set failed_attempts = -1 where id = ?", id));
        assertRejected(() -> jdbc.update("update employee set pin_hash = null where id = ?", id));
        assertRejected(() -> jdbc.update("update employee set branch_id = null where id = ?", id));
        assertRejected(() -> jdbc.update("update employee set code = '' where id = ?", id));
        assertRejected(() -> jdbc.update("update employee set code = ? where id = ?", "A".repeat(21), id));
        assertThat(jdbc.queryForObject("select token_version from employee where id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void workScheduleHoursAndToleranceAreChecked() {
        TestData.Tenant t = data.tenant();
        long emp = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String sql = """
                insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time, grace_minutes)
                values (?, ?, ?, ?::time, ?::time, ?)
                """;
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, 1, "08:00", "08:00", 0));
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, 2, "08:00", "17:00", -1));
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, 3, "08:00", "17:00", 241));
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, 0, "08:00", "17:00", 0));
        jdbc.update(sql, t.companyId(), emp, 7, "08:00", "17:00", 240);
        // Overnight shifts are allowed by the schema (semantics are Stage 2's decision).
        jdbc.update(sql, t.companyId(), emp, 6, "22:00", "06:00", 0);
    }

    @Test
    void timeEntryGeoAndDistanceChecks() {
        TestData.Tenant t = data.tenant();
        long emp = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String sql = """
                insert into time_entry (company_id, employee_id, branch_id, type, source, latitude, longitude, distance_meters, client_uuid, work_date)
                values (?, ?, ?, 'CLOCK_OUT', 'QR', ?, ?, ?, ?, current_date)
                """;
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, t.branchId(), 91, 0, 10, UUID.randomUUID()));
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, t.branchId(), 0, 181, 10, UUID.randomUUID()));
        assertRejected(() -> jdbc.update(sql, t.companyId(), emp, t.branchId(), 0, 0, -1, UUID.randomUUID()));
        assertRejected(() -> jdbc.update(
                "insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date) "
                        + "values (?, ?, ?, 'OTRO', 'QR', ?, current_date)", t.companyId(), emp, t.branchId(), UUID.randomUUID()));
        assertRejected(() -> jdbc.update(
                "insert into time_entry (company_id, employee_id, branch_id, type, source) "
                        + "values (?, ?, ?, 'CLOCK_IN', 'QR')", t.companyId(), emp, t.branchId()));
        // GPS-less marks (QR/tablet) are valid: coordinates and distance are nullable.
        jdbc.update("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date)
                values (?, ?, ?, 'CLOCK_OUT', 'TABLET', ?, current_date)
                """, t.companyId(), emp, t.branchId(), UUID.randomUUID());
    }

    @Test
    void timeEntryDefaultsAreApplied() {
        TestData.Tenant t = data.tenant();
        long emp = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long id = data.timeEntry(t.companyId(), emp, t.branchId());
        assertThat(jdbc.queryForObject("select status from time_entry where id = ?", String.class, id))
                .isEqualTo("VALID");
        assertThat(jdbc.queryForObject("select synced_late from time_entry where id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("select server_time is not null from time_entry where id = ?", Boolean.class,
                id)).isTrue();
    }

    @Test
    void evidenceTypeAndAdjustmentReasonAreChecked() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        long emp = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long timeEntry = data.timeEntry(t.companyId(), emp, t.branchId());
        assertRejected(() -> jdbc.update("insert into evidence (company_id, time_entry_id, type) values (?, ?, 'VIDEO')",
                t.companyId(), timeEntry));
        // GPS evidence has no file: storage_key is nullable.
        jdbc.update("insert into evidence (company_id, time_entry_id, type) values (?, ?, 'GPS')", t.companyId(), timeEntry);
        assertRejected(() -> jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, new_value)
                values (?, ?, ?, '   ', 'status', 'VALID')
                """, t.companyId(), timeEntry, admin));
        assertRejected(() -> jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, new_value)
                values (?, ?, ?, 'reason', 'status', null)
                """, t.companyId(), timeEntry, admin));
    }

    @Test
    void sameEmployeeAndTimeEntryChildrenInTheSameCompanyAreAccepted() {
        // Positive control for the composite FKs: matching company_id always works.
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        long emp = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long timeEntry = data.timeEntry(t.companyId(), emp, t.branchId());
        jdbc.update("insert into evidence (company_id, time_entry_id, type) values (?, ?, 'PHOTO')", t.companyId(), timeEntry);
        jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, reason, field, old_value, new_value)
                values (?, ?, ?, 'Corrección', 'status', 'PENDING_REVIEW', 'VALID')
                """, t.companyId(), timeEntry, admin);
    }

    @Test
    void branchOfAnotherCompanyCannotBeReassignedToAnEmployee() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long emp = data.employee(a.companyId(), a.branchId(), "EMPLOYEE");
        assertRejected(() -> jdbc.update("update employee set branch_id = ? where id = ?", b.branchId(), emp));
    }

    @Test
    void companyIdOfAChildCannotBeChangedToAnotherTenant() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long emp = data.employee(a.companyId(), a.branchId(), "EMPLOYEE");
        long timeEntry = data.timeEntry(a.companyId(), emp, a.branchId());
        assertRejected(() -> jdbc.update("update time_entry set company_id = ? where id = ?", b.companyId(), timeEntry));
    }
}
