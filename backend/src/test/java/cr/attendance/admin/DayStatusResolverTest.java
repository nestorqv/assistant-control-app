package cr.attendance.admin;

import static cr.attendance.admin.AdminRulesFixtures.CR;
import static cr.attendance.admin.AdminRulesFixtures.DAY;
import static cr.attendance.admin.AdminRulesFixtures.at;
import static cr.attendance.admin.AdminRulesFixtures.entry;
import static cr.attendance.admin.AdminRulesFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.employee.WorkSchedule;
import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DayStatusResolverTest {

    private static final Duration MAX = Duration.ofHours(16);
    private static final WorkSchedule OFFICE = schedule("08:00", "17:00", 5);

    private static DayStatus resolve(TimeEntry in, TimeEntry out, WorkSchedule s, Instant now) {
        return resolve(in, out, s, now, DAY);
    }

    private static DayStatus resolve(TimeEntry in, TimeEntry out, WorkSchedule s, Instant now, LocalDate date) {
        Integer late = in == null ? null : in.getLateMinutes();
        return DayStatusResolver.resolve(in, out, s, late, date, now, CR, MAX);
    }

    private static TimeEntry pending(TimeEntryType type, String time, ReviewReason... reasons) {
        return entry(type, time, TimeEntryStatus.PENDING_REVIEW, 0, EnumSet.copyOf(java.util.List.of(reasons)));
    }

    private static TimeEntry rejected(TimeEntryType type, String time) {
        return entry(type, time, TimeEntryStatus.REJECTED, 0, EnumSet.noneOf(ReviewReason.class));
    }

    @Test
    void rejectedBeatsPending() {
        TimeEntry in = rejected(TimeEntryType.CLOCK_IN, "08:00");
        TimeEntry out = pending(TimeEntryType.CLOCK_OUT, "17:00", ReviewReason.OUTSIDE_GEOFENCE);
        assertThat(resolve(in, out, OFFICE, at("18:00"))).isEqualTo(DayStatus.REJECTED);
    }

    @Test
    void pendingOutsideGeofenceIsOutsideAreaAndOtherPendingIsNeedsReview() {
        assertThat(resolve(pending(TimeEntryType.CLOCK_IN, "08:01", ReviewReason.OUTSIDE_GEOFENCE), null, OFFICE,
                at("09:00"))).isEqualTo(DayStatus.OUTSIDE_AREA);
        assertThat(resolve(pending(TimeEntryType.CLOCK_IN, "08:01", ReviewReason.SYNCED_LATE), null, OFFICE,
                at("09:00"))).isEqualTo(DayStatus.NEEDS_REVIEW);
    }

    @Test
    void pendingBeatsComplete() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, "08:00");
        TimeEntry out = pending(TimeEntryType.CLOCK_OUT, "17:00", ReviewReason.CLOCK_OUT_AFTER_SCHEDULE);
        assertThat(resolve(in, out, OFFICE, at("18:00"))).isEqualTo(DayStatus.NEEDS_REVIEW);
    }

    @Test
    void completeWhenBothEntriesAreValid() {
        assertThat(resolve(entry(TimeEntryType.CLOCK_IN, "08:00"), entry(TimeEntryType.CLOCK_OUT, "17:00"), OFFICE,
                at("18:00"))).isEqualTo(DayStatus.COMPLETE);
    }

    @Test
    void inProgressIsLateOnTimeOrUnscheduled() {
        TimeEntry late = entry(TimeEntryType.CLOCK_IN, "08:17", TimeEntryStatus.VALID, 17, EnumSet.noneOf(ReviewReason.class));
        assertThat(resolve(late, null, OFFICE, at("09:00"))).isEqualTo(DayStatus.LATE);
        assertThat(resolve(entry(TimeEntryType.CLOCK_IN, "07:58"), null, OFFICE, at("09:00")))
                .isEqualTo(DayStatus.ON_TIME);
        TimeEntry noSchedule = entry(TimeEntryType.CLOCK_IN, "09:00", TimeEntryStatus.VALID, null,
                EnumSet.noneOf(ReviewReason.class));
        assertThat(resolve(noSchedule, null, null, at("10:00"))).isEqualTo(DayStatus.UNSCHEDULED);
    }

    @Test
    void missingClockOutAfterTheMaximumShiftButNotJustBefore() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, "06:00");
        // 15 h 59 min after the clock-in: still in progress. 16 h 01 min: forgotten.
        assertThat(resolve(in, null, OFFICE, at("06:00").plus(Duration.ofHours(15).plusMinutes(59))))
                .isEqualTo(DayStatus.ON_TIME);
        assertThat(resolve(in, null, OFFICE, at("06:00").plus(Duration.ofHours(16).plusMinutes(1))))
                .isEqualTo(DayStatus.MISSING_CLOCK_OUT);
    }

    @Test
    void aClockInOfAPastDateWithoutClockOutIsMissingClockOut() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, "08:00");
        assertThat(resolve(in, null, OFFICE, at("09:00").plus(Duration.ofDays(1)), DAY)).isEqualTo(DayStatus.MISSING_CLOCK_OUT);
    }

    @Test
    void notYetDueBoundaryIsStartPlusGrace() {
        // Start 08:00 + 5 min grace: the minute before is not due yet, the boundary itself is "not clocked".
        assertThat(resolve(null, null, OFFICE, at("08:04"))).isEqualTo(DayStatus.NOT_YET_DUE);
        assertThat(resolve(null, null, OFFICE, at("08:04").plusSeconds(59))).isEqualTo(DayStatus.NOT_YET_DUE);
        assertThat(resolve(null, null, OFFICE, at("08:05"))).isEqualTo(DayStatus.NOT_CLOCKED);
    }

    @Test
    void pastDateIsNotClockedAndFutureDateIsNotYetDue() {
        assertThat(resolve(null, null, OFFICE, at("07:00").plus(Duration.ofDays(1)), DAY)).isEqualTo(DayStatus.NOT_CLOCKED);
        assertThat(resolve(null, null, OFFICE, at("07:00"), DAY.plusDays(3))).isEqualTo(DayStatus.NOT_YET_DUE);
        assertThat(resolve(null, null, null, at("07:00"), DAY.plusDays(3))).isEqualTo(DayStatus.DAY_OFF);
    }

    @Test
    void noScheduleAndNoEntryIsDayOff() {
        assertThat(resolve(null, null, null, at("12:00"))).isEqualTo(DayStatus.DAY_OFF);
    }

    @Test
    void overnightScheduleStartsOnTheWorkDate() {
        WorkSchedule night = schedule("22:00", "06:00", 5);
        assertThat(resolve(null, null, night, at("21:00"))).isEqualTo(DayStatus.NOT_YET_DUE);
        assertThat(resolve(null, null, night, at("22:06"))).isEqualTo(DayStatus.NOT_CLOCKED);
    }

    @Test
    void anOvernightShiftStillOpenTheNextMorningIsInProgressNotMissing() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, "22:00");
        Instant halfPastMidnight = at("22:00").plus(Duration.ofHours(2).plusMinutes(30));
        assertThat(resolve(in, null, schedule("22:00", "06:00", 5), halfPastMidnight, DAY)).isEqualTo(DayStatus.ON_TIME);
    }

    @Test
    void aClockInOlderThanTheMaximumShiftIsMissingWhateverTheDate() {
        TimeEntry in = entry(TimeEntryType.CLOCK_IN, "08:00");
        assertThat(resolve(in, null, OFFICE, at("08:00").plus(Duration.ofHours(16)), DAY)).isEqualTo(DayStatus.ON_TIME);
        assertThat(resolve(in, null, OFFICE, at("08:00").plus(Duration.ofHours(16)).plusSeconds(1), DAY))
                .isEqualTo(DayStatus.MISSING_CLOCK_OUT);
    }
}
