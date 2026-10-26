package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.employee.WorkSchedule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class LatenessCalculatorTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    private static WorkSchedule schedule(String start, String end, int grace) {
        return new WorkSchedule(1L, 1L, 3, LocalTime.parse(start), LocalTime.parse(end), grace);
    }

    private static Instant at(String time) {
        return ZonedDateTime.of(DAY, LocalTime.parse(time), CR).toInstant();
    }

    @Test
    void earlyAndExactStartAreOnTime() {
        WorkSchedule s = schedule("08:00", "17:00", 5);
        assertThat(LatenessCalculator.lateMinutes(at("07:30"), DAY, CR, s)).isZero();
        assertThat(LatenessCalculator.lateMinutes(at("08:00"), DAY, CR, s)).isZero();
    }

    @Test
    void graceBoundaryCountsFromTheScheduledStart() {
        WorkSchedule s = schedule("08:00", "17:00", 5);
        assertThat(LatenessCalculator.lateMinutes(at("08:05:59"), DAY, CR, s)).isZero();
        assertThat(LatenessCalculator.lateMinutes(at("08:06:00"), DAY, CR, s)).isEqualTo(6);
        assertThat(LatenessCalculator.lateMinutes(at("08:12"), DAY, CR, s)).isEqualTo(12);
    }

    @Test
    void noScheduleGivesNull() {
        assertThat(LatenessCalculator.lateMinutes(at("08:12"), DAY, CR, null)).isNull();
    }

    @Test
    void overnightScheduleUsesOnlyTheStartTime() {
        WorkSchedule s = schedule("22:00", "06:00", 5);
        assertThat(LatenessCalculator.lateMinutes(at("21:55"), DAY, CR, s)).isZero();
        assertThat(LatenessCalculator.lateMinutes(at("22:12"), DAY, CR, s)).isEqualTo(12);
    }

    @Test
    void theScheduleIsInTheCompanyZoneNotUtc() {
        WorkSchedule s = schedule("08:00", "17:00", 0);
        Instant instant = at("08:30"); // 14:30 UTC
        assertThat(LatenessCalculator.lateMinutes(instant, DAY, CR, s)).isEqualTo(30);
        // Read as UTC the same instant is 14:30 against an 08:00 start: a different answer.
        assertThat(LatenessCalculator.lateMinutes(instant, DAY, ZoneId.of("UTC"), s)).isEqualTo(390);
    }

    @Test
    void springForwardDayInANewYorkZone() {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate dst = LocalDate.of(2026, 3, 8); // clocks jump 02:00 -> 03:00
        WorkSchedule s = schedule("08:00", "17:00", 0);
        Instant at0815 = ZonedDateTime.of(dst, LocalTime.of(8, 15), ny).toInstant();
        assertThat(LatenessCalculator.lateMinutes(at0815, dst, ny, s)).isEqualTo(15);
    }
}
