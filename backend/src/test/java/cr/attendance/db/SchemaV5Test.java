package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V5: version columns, blank-name checks and the optional reason of an admin action. */
@IntegrationTest
class SchemaV5Test {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Test
    void versionIsNotNullAndStartsAtZeroOnBothTables() {
        TestData.Tenant t = data.tenant();
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        assertThat(jdbc.queryForObject("select version from employee where id = ?", Integer.class, employee)).isZero();
        assertThat(jdbc.queryForObject("select version from branch where id = ?", Integer.class, t.branchId())).isZero();
        for (String table : new String[] {"employee", "branch"}) {
            assertThat(jdbc.queryForObject("""
                    select is_nullable from information_schema.columns
                     where table_name = ? and column_name = 'version'
                    """, String.class, table)).isEqualTo("NO");
        }
    }

    @Test
    void blankNamesAreRefusedByTheDatabaseToo() {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> jdbc.update(
                "insert into branch (company_id, name, latitude, longitude) values (?, '   ', 9.9, -84.0)",
                t.companyId())).hasMessageContaining("ck_branch_name");
        assertThatThrownBy(() -> jdbc.update("""
                insert into employee (company_id, branch_id, code, name, pin_hash, role)
                values (?, ?, ?, '   ', ?, 'EMPLOYEE')
                """, t.companyId(), t.branchId(), data.randomCode("B"), data.pinHash()))
                .hasMessageContaining("ck_employee_name");
    }

    private void audit(TestData.Tenant t, long actor, String code, String note) {
        jdbc.update("""
                insert into audit_log (company_id, actor_id, action, target_type, target_id, reason_code, reason_note)
                values (?, ?, 'PIN_RESET', 'EMPLOYEE', ?, ?, ?)
                """, t.companyId(), actor, actor, code, note);
    }

    @Test
    void reasonCodeMustHaveTheEnumFormatAndANoteNeedsACode() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        audit(t, admin, "FORGOT_PIN", null);
        audit(t, admin, "OTHER", "Solicitado por la gerencia");
        audit(t, admin, null, null);
        assertThatThrownBy(() -> audit(t, admin, "forgot", null)).hasMessageContaining("ck_audit_log_reason_code");
        assertThatThrownBy(() -> audit(t, admin, null, "note without a code"))
                .hasMessageContaining("ck_audit_log_reason_note");
        assertThatThrownBy(() -> audit(t, admin, "OTHER", "   ")).hasMessageContaining("ck_audit_log_reason_note");
    }

    @Test
    void existingAuditRowsHaveNoReasonAndTheTriggerStillRefusesAnUpdate() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        jdbc.update("insert into audit_log (company_id, actor_id, action, target_type) values (?, ?, 'REPORT_EXPORT', 'REPORT')",
                t.companyId(), admin);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log where company_id = ? and (reason_code is not null or reason_note is not null)
                """, Integer.class, t.companyId())).isZero();
        assertThatThrownBy(() -> jdbc.update("update audit_log set reason_code = 'OTHER', reason_note = 'x' where company_id = ?", t.companyId()))
                .isInstanceOf(Exception.class);
    }
}
