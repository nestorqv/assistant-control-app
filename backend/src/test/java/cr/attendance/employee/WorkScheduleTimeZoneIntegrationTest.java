package cr.attendance.employee;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.time.LocalTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A schedule hour is a wall-clock time (column type "time"), so what the entity writes must be what
 * the database stores, whatever the JVM default time zone is.
 */
@IntegrationTest
class WorkScheduleTimeZoneIntegrationTest {

    @Autowired
    private WorkScheduleRepository workSchedules;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private String saveAndReadAsText(String jvmZone) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
            TestData.Tenant t = data.tenant();
            long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
            WorkSchedule saved = workSchedules.save(
                    new WorkSchedule(t.companyId(), employeeId, 1, LocalTime.of(6, 0), LocalTime.of(14, 30), 5));
            return jdbc.queryForObject("select start_time::text || '-' || end_time::text from work_schedule "
                    + "where id = ?", String.class, saved.getId());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void controlJvmInUtcStoresTheHoursAsWritten() {
        assertThat(saveAndReadAsText("UTC")).isEqualTo("06:00:00-14:30:00");
    }

    // Regression: with hibernate.jdbc.time_zone=UTC and a JVM in America/Costa_Rica (UTC-6), 06:00 used to be
    // stored as 12:00. WorkSchedule now binds LocalTime directly (see @JdbcType there).
    @Test
    void hoursAreStoredAsWrittenWhenTheJvmIsInCostaRica() {
        assertThat(saveAndReadAsText("America/Costa_Rica")).isEqualTo("06:00:00-14:30:00");
    }

    @Test
    void hoursAreStoredAsWrittenInZonesAheadOfUtcToo() {
        assertThat(saveAndReadAsText("Asia/Tokyo")).isEqualTo("06:00:00-14:30:00");
        assertThat(saveAndReadAsText("Pacific/Kiritimati")).isEqualTo("06:00:00-14:30:00");
    }

    @Test
    void timestamptzColumnsStillStoreTheCorrectInstantWhateverTheJvmZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
            TestData.Tenant t = data.tenant();
            long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
            Instant instant = Instant.parse("2026-10-21T12:34:56Z");
            assertThat(employees.recordConsent(employeeId, t.companyId(), instant)).isEqualTo(1);
            Instant saved = jdbc.queryForObject(
                    "select consent_at from employee where id = ?", java.sql.Timestamp.class, employeeId)
                    .toInstant();
            assertThat(saved).isEqualTo(instant);
            assertThat(employees.findByIdAndCompanyId(employeeId, t.companyId()).orElseThrow()
                    .getConsentAt()).isEqualTo(instant);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void roundTripThroughTheEntityIsStableInCostaRica() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
            TestData.Tenant t = data.tenant();
            long employeeId = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
            workSchedules.save(new WorkSchedule(t.companyId(), employeeId, 2, LocalTime.of(6, 0), LocalTime.of(14, 30), 5));
            WorkSchedule readBack = workSchedules.findAllByCompanyIdAndEmployeeId(t.companyId(), employeeId).get(0);
            assertThat(readBack.getStartTime()).isEqualTo(LocalTime.of(6, 0));
            assertThat(readBack.getEndTime()).isEqualTo(LocalTime.of(14, 30));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
