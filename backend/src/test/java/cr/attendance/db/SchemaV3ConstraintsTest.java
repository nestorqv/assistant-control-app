package cr.attendance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stage 2 (V3): every new CHECK and unique rule rejects its bad case. */
@IntegrationTest
class SchemaV3ConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private static final String UUID_PART = "123e4567-e89b-12d3-a456-426614174000";

    private void rejected(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void timeEntryChecks() {
        TestData.Tenant t = data.tenant();
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long clockIn = data.timeEntry(t.companyId(), employee, t.branchId());
        long clockOut = data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_OUT", LocalDate.now());

        rejected(() -> jdbc.update("update time_entry set latitude = 9.9 where id = ?", clockIn),
                "ck_time_entry_location_pair");
        jdbc.update("update time_entry set latitude = 9.9, longitude = -84.0 where id = ?", clockIn);

        rejected(() -> jdbc.update("update time_entry set late_minutes = 5 where id = ?", clockOut),
                "ck_time_entry_late_minutes");
        rejected(() -> jdbc.update("update time_entry set late_minutes = -1 where id = ?", clockIn),
                "ck_time_entry_late_minutes");
        jdbc.update("update time_entry set late_minutes = 0 where id = ?", clockIn);

        rejected(() -> jdbc.update("update time_entry set gps_accuracy_meters = -1 where id = ?", clockIn),
                "ck_time_entry_gps_accuracy");

        rejected(() -> jdbc.update("update time_entry set review_reasons = 'no_gps' where id = ?", clockIn),
                "ck_time_entry_review_reasons");
        rejected(() -> jdbc.update("update time_entry set review_reasons = 'NO_GPS,' where id = ?", clockIn),
                "ck_time_entry_review_reasons");
        jdbc.update("update time_entry set review_reasons = 'NO_GPS,SYNCED_LATE' where id = ?", clockIn);

        rejected(() -> jdbc.update("update time_entry set work_date = null where id = ?", clockIn), "work_date");
    }

    @Test
    void oneClockInAndOneClockOutPerEmployeePerWorkDate() {
        TestData.Tenant t = data.tenant();
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        LocalDate day = LocalDate.of(2026, 10, 22);
        data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_IN", day);
        data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_OUT", day);
        data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_IN", day.plusDays(1));
        rejected(() -> data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_IN", day),
                "uq_time_entry_employee_work_date_type");
        rejected(() -> data.timeEntry(t.companyId(), employee, t.branchId(), "CLOCK_OUT", day),
                "uq_time_entry_employee_work_date_type");
    }

    @Test
    void evidenceChecks() {
        TestData.Tenant t = data.tenant();
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long entry = data.timeEntry(t.companyId(), employee, t.branchId());
        String insert = "insert into evidence (company_id, time_entry_id, type, storage_key) values (?, ?, ?, ?)";
        String goodKey = t.companyId() + "/2026-10/" + UUID_PART + ".jpg";

        rejected(() -> jdbc.update(insert, t.companyId(), entry, "GPS", goodKey), "ck_evidence_storage_key_type");
        rejected(() -> jdbc.update(insert, t.companyId(), entry, "PHOTO", t.companyId() + "/2026-10/../x.jpg"),
                "ck_evidence_storage_key_format");
        rejected(() -> jdbc.update(insert, t.companyId(), entry, "PHOTO", t.companyId() + "/2026-10/" + UUID_PART + ".png"),
                "ck_evidence_storage_key_format");
        rejected(() -> jdbc.update(insert, t.companyId(), entry, "PHOTO", (t.companyId() + 1) + "/2026-10/" + UUID_PART + ".jpg"),
                "ck_evidence_storage_key_company");

        jdbc.update(insert, t.companyId(), entry, "PHOTO", goodKey);
        rejected(() -> jdbc.update(insert, t.companyId(), entry, "PHOTO", null), "uq_evidence_time_entry_type");

        // Retention (Stage 5): a PHOTO row may lose its key. GPS and QR rows never have one.
        jdbc.update("update evidence set storage_key = null where time_entry_id = ? and type = 'PHOTO'", entry);
        jdbc.update(insert, t.companyId(), entry, "GPS", null);
        jdbc.update(insert, t.companyId(), entry, "QR", null);
    }

    @Test
    void aPhotoRowWithoutAKeyIsAcceptedOnItsOwn() {
        TestData.Tenant t = data.tenant();
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long entry = data.timeEntry(t.companyId(), employee, t.branchId());
        jdbc.update("insert into evidence (company_id, time_entry_id, type) values (?, ?, 'PHOTO')", t.companyId(),
                entry);
        assertThat(jdbc.queryForObject("select count(*) from evidence where time_entry_id = ?", Integer.class, entry))
                .isEqualTo(1);
    }
}
