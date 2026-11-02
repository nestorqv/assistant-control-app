package cr.attendance.admin;

import cr.attendance.employee.WorkSchedule;
import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The day-status priority table of the plan. Pure: shared by the dashboard and the export. */
public final class DayStatusResolver {

    private DayStatusResolver() {
    }

    /** in/out may be null; schedule is the employee schedule for the ISO weekday of date, or null. */
    public static DayStatus resolve(TimeEntry in, TimeEntry out, WorkSchedule schedule, Integer lateMinutes,
            LocalDate date, Instant now, ZoneId zone, Duration maxShift) {
        LocalDate today = now.atZone(zone).toLocalDate();
        if (isStatus(in, TimeEntryStatus.REJECTED) || isStatus(out, TimeEntryStatus.REJECTED)) {
            return DayStatus.REJECTED;
        }
        if (pendingWith(in, ReviewReason.OUTSIDE_GEOFENCE) || pendingWith(out, ReviewReason.OUTSIDE_GEOFENCE)) {
            return DayStatus.OUTSIDE_AREA;
        }
        if (isStatus(in, TimeEntryStatus.PENDING_REVIEW) || isStatus(out, TimeEntryStatus.PENDING_REVIEW)) {
            return DayStatus.NEEDS_REVIEW;
        }
        if (in != null && out == null) {
            // Only the maximum shift decides: an overnight shift that started yesterday is still in progress.
            boolean stale = now.isAfter(in.getEffectiveTime().plus(maxShift));
            if (stale) {
                return DayStatus.MISSING_CLOCK_OUT;
            }
        }
        if (in != null && out != null) {
            return DayStatus.COMPLETE;
        }
        if (in != null) {
            if (lateMinutes == null) {
                return DayStatus.UNSCHEDULED;
            }
            return lateMinutes > 0 ? DayStatus.LATE : DayStatus.ON_TIME;
        }
        if (schedule == null) {
            return DayStatus.DAY_OFF;
        }
        if (date.isAfter(today)) {
            return DayStatus.NOT_YET_DUE;
        }
        if (date.equals(today)) {
            Instant dueAt = date.atTime(schedule.getStartTime()).atZone(zone).toInstant()
                    .plus(Duration.ofMinutes(schedule.getGraceMinutes()));
            if (now.isBefore(dueAt)) {
                return DayStatus.NOT_YET_DUE;
            }
        }
        return DayStatus.NOT_CLOCKED;
    }

    private static boolean isStatus(TimeEntry e, TimeEntryStatus status) {
        return e != null && e.getStatus() == status;
    }

    private static boolean pendingWith(TimeEntry e, ReviewReason reason) {
        return isStatus(e, TimeEntryStatus.PENDING_REVIEW) && e.getReviewReasons().contains(reason);
    }
}
