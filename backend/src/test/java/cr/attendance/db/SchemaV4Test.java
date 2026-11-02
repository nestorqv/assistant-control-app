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
class SchemaV4Test {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private long timeEntry(TestData.Tenant t, long employeeId) {
        return data.timeEntry(t.companyId(), employeeId, t.branchId());
    }

    @Test
    void effectiveTimeIsRequiredAndAdminIsAValidSourceButKioskIsNot() {
        TestData.Tenant t = data.tenant();
        long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String withEffective = """
                insert into time_entry (effective_time, company_id, employee_id, branch_id, type, source, client_uuid, work_date)
                values (now(), ?, ?, ?, 'CLOCK_IN', ?, ?, current_date)
                """;
        jdbc.update(withEffective, t.companyId(), employeeId, t.branchId(), "ADMIN", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(withEffective, t.companyId(), employeeId, t.branchId(), "KIOSK",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_time_entry_source");
        assertThatThrownBy(() -> jdbc.update("""
                insert into time_entry (company_id, employee_id, branch_id, type, source, client_uuid, work_date)
                values (?, ?, ?, 'CLOCK_OUT', 'ADMIN', ?, current_date)
                """, t.companyId(), employeeId, t.branchId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("effective_time");
    }

    @Test
    void theMigrationBackfilledEffectiveTimeColumnIsNotNullable() {
        assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_name = 'time_entry' and column_name = 'effective_time'
                """, String.class)).isEqualTo("NO");
    }

    @Test
    void adjustmentActionFieldAndReasonLengthAreConstrained() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long entry = timeEntry(t, employee);
        String sql = """
                insert into adjustment (company_id, time_entry_id, admin_id, action, reason, field, old_value, new_value)
                values (?, ?, ?, ?, ?, ?, 'a', 'b')
                """;
        jdbc.update(sql, t.companyId(), entry, admin, "APPROVE", "ok reason", "status");
        assertThatThrownBy(() -> jdbc.update(sql, t.companyId(), entry, admin, "DELETE", "ok reason", "status"))
                .hasMessageContaining("ck_adjustment_action");
        assertThatThrownBy(() -> jdbc.update(sql, t.companyId(), entry, admin, "APPROVE", "ok reason", "server_time"))
                .hasMessageContaining("ck_adjustment_field");
        assertThatThrownBy(() -> jdbc.update(sql, t.companyId(), entry, admin, "APPROVE", "x".repeat(501), "status"))
                .hasMessageContaining("ck_adjustment_reason_length");
        jdbc.update(sql, t.companyId(), entry, admin, "APPROVE", "x".repeat(500), "status");
    }

    @Test
    void branchesAreActiveByDefault() {
        TestData.Tenant t = data.tenant();
        assertThat(jdbc.queryForObject("select active from branch where id = ?", Boolean.class, t.branchId())).isTrue();
    }

    @Test
    void auditLogRejectsAnActorOfAnotherCompanyAndBadActionFormats() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long adminB = data.employee(b.companyId(), null, "ADMIN");
        String sql = "insert into audit_log (company_id, actor_id, action, target_type, target_id, details) "
                + "values (?, ?, ?, ?, 1, 'x')";
        assertThatThrownBy(() -> jdbc.update(sql, a.companyId(), adminB, "PHOTO_VIEW", "TIME_ENTRY"))
                .hasMessageContaining("fk_audit_log_actor_company");
        assertThatThrownBy(() -> jdbc.update(sql, b.companyId(), adminB, "photo view", "TIME_ENTRY"))
                .hasMessageContaining("ck_audit_log_action");
        assertThatThrownBy(() -> jdbc.update(sql, b.companyId(), adminB, "PHOTO_VIEW", "PHOTO"))
                .hasMessageContaining("ck_audit_log_target_type");
        jdbc.update(sql, b.companyId(), adminB, "NEW_FUTURE_ACTION", "REPORT");
    }

    @Test
    void adjustmentAndAuditLogAreAppendOnlyButInsertWorks() {
        TestData.Tenant t = data.tenant();
        long admin = data.employee(t.companyId(), null, "ADMIN");
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long entry = timeEntry(t, employee);
        jdbc.update("""
                insert into adjustment (company_id, time_entry_id, admin_id, action, reason, field, new_value)
                values (?, ?, ?, 'REJECT', 'motivo valido', 'status', 'REJECTED')
                """, t.companyId(), entry, admin);
        jdbc.update("insert into audit_log (company_id, actor_id, action, target_type) values (?, ?, 'REPORT_EXPORT', 'REPORT')",
                t.companyId(), admin);

        for (String table : List.of("adjustment", "audit_log")) {
            for (String sql : List.of("update " + table + " set company_id = company_id",
                    "delete from " + table, "truncate table " + table)) {
                assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("append-only table: " + table);
            }
        }
    }

    @Test
    void theFourAppendOnlyTriggersExist() {
        List<String> triggers = jdbc.queryForList(
                "select tgname from pg_trigger where not tgisinternal and tgname like 'tr\\_%' order by tgname", String.class);
        assertThat(triggers).containsExactlyInAnyOrder("tr_adjustment_append_only", "tr_adjustment_no_truncate",
                "tr_audit_log_append_only", "tr_audit_log_no_truncate");
    }

    @Test
    void thePendingReviewIndexIsPartialOnCompanyAndWorkDate() {
        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ix_time_entry_pending_review'", String.class);
        assertThat(definition).contains("(company_id, work_date)").contains("PENDING_REVIEW");
    }
}
