package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * work_date is a calendar date (column type "date"), so what the entity writes must be what the database
 * stores, whatever the JVM default zone is. Same bug class as WorkScheduleTimeZoneIntegrationTest.
 */
@IntegrationTest
class WorkDateTimeZoneIntegrationTest {

    @Autowired
    private TimeEntryRepository timeEntries;

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
            TimeEntry saved = timeEntries.save(new TimeEntry(t.companyId(), employeeId, t.branchId(),
                    TimeEntryType.CLOCK_IN, Instant.now(), TimeEntrySource.MOBILE, UUID.randomUUID(),
                    LocalDate.of(2026, 10, 22)));
            LocalDate readBack = timeEntries.findByIdAndCompanyId(saved.getId(), t.companyId()).orElseThrow()
                    .getWorkDate();
            assertThat(readBack).isEqualTo(LocalDate.of(2026, 10, 22));
            return jdbc.queryForObject("select work_date::text from time_entry where id = ?", String.class,
                    saved.getId());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void theDateIsStoredAsWrittenWhateverTheJvmZone() {
        assertThat(saveAndReadAsText("UTC")).isEqualTo("2026-10-22");
        assertThat(saveAndReadAsText("America/Costa_Rica")).isEqualTo("2026-10-22");
        assertThat(saveAndReadAsText("Asia/Tokyo")).isEqualTo("2026-10-22");
        assertThat(saveAndReadAsText("Pacific/Kiritimati")).isEqualTo("2026-10-22");
    }
}
