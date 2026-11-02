package cr.attendance.admin;

import static cr.attendance.admin.AdminRulesFixtures.at;
import static cr.attendance.admin.AdminRulesFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class WorkedTimeTest {

    private static TimeEntry in(String time) {
        return entry(TimeEntryType.CLOCK_IN, time);
    }

    private static TimeEntry out(String time, TimeEntryStatus status) {
        return entry(TimeEntryType.CLOCK_OUT, time, status, null, EnumSet.noneOf(ReviewReason.class));
    }

    @Test
    void minutesAreFlooredFromTheEffectiveTimes() {
        assertThat(WorkedTime.workedMinutes(in("06:00"), out("14:04", TimeEntryStatus.VALID))).isEqualTo(484);
    }

    @Test
    void aCorrectionMovesTheWorkedTimeAndNotTheReceiptTime() {
        TimeEntry clockOut = out("17:00", TimeEntryStatus.VALID);
        clockOut.correctEffectiveTime(at("17:30"), null);
        assertThat(clockOut.getServerTime()).isEqualTo(at("17:00"));
        assertThat(WorkedTime.workedMinutes(in("08:00"), clockOut)).isEqualTo(570);
    }

    @Test
    void approvedOnlyWhenBothEntriesAreValid() {
        assertThat(WorkedTime.approvedMinutes(in("08:00"), out("17:00", TimeEntryStatus.VALID))).isEqualTo(540);
        assertThat(WorkedTime.approvedMinutes(in("08:00"), out("17:00", TimeEntryStatus.PENDING_REVIEW))).isZero();
        assertThat(WorkedTime.pendingMinutes(in("08:00"), out("17:00", TimeEntryStatus.PENDING_REVIEW)))
                .isEqualTo(540);
        assertThat(WorkedTime.pendingMinutes(in("08:00"), out("17:00", TimeEntryStatus.VALID))).isZero();
    }

    @Test
    void rejectedOrMissingEntriesGiveNothing() {
        assertThat(WorkedTime.workedMinutes(in("08:00"), out("17:00", TimeEntryStatus.REJECTED))).isNull();
        assertThat(WorkedTime.workedMinutes(in("08:00"), null)).isNull();
        assertThat(WorkedTime.approvedMinutes(in("08:00"), out("17:00", TimeEntryStatus.REJECTED))).isZero();
        assertThat(WorkedTime.pendingMinutes(in("08:00"), null)).isZero();
    }
}
