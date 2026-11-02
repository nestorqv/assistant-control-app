package cr.attendance.admin;

import cr.attendance.employee.WorkSchedule;
import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Builders for the pure rule tests (no Spring, no database). */
final class AdminRulesFixtures {

    static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    static final LocalDate DAY = LocalDate.of(2026, 10, 21); // a Wednesday

    private AdminRulesFixtures() {
    }

    static Instant at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }

    static WorkSchedule schedule(String start, String end, int grace) {
        return new WorkSchedule(1L, 1L, 3, LocalTime.parse(start), LocalTime.parse(end), grace);
    }

    static TimeEntry entry(TimeEntryType type, String time) {
        return entry(type, time, TimeEntryStatus.VALID, Integer.valueOf(0), EnumSet.noneOf(ReviewReason.class));
    }

    static TimeEntry entry(TimeEntryType type, String time, TimeEntryStatus status, Integer late,
            Set<ReviewReason> reasons) {
        Instant t = at(time);
        return new TimeEntry(1L, 1L, 1L, type, t, TimeEntrySource.MOBILE, UUID.randomUUID(), DAY)
                .withEvidence(t, 9.9, -84.0, 10, 10, 0, type == TimeEntryType.CLOCK_IN ? late : null, false, status,
                        reasons);
    }
}
