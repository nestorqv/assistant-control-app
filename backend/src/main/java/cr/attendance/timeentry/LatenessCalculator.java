package cr.attendance.timeentry;

import cr.attendance.employee.WorkSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public final class LatenessCalculator {

    private LatenessCalculator() {
    }

    /**
     * Minutes late counted from the scheduled start once the grace period is exceeded, 0 when on time or
     * early, null when there is no schedule for that day. Only start_time is used (overnight schedules too).
     */
    public static Integer lateMinutes(Instant clockIn, LocalDate workDate, ZoneId zone, WorkSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        Instant scheduledStart = workDate.atTime(schedule.getStartTime()).atZone(zone).toInstant();
        long minutesLate = Duration.between(scheduledStart, clockIn).toMinutes();
        return minutesLate <= schedule.getGraceMinutes() ? 0 : (int) minutesLate;
    }

    /**
     * True when the clock-out is later than the scheduled end plus the tolerance. Overnight-aware: an end that is
     * not after the start belongs to the next calendar day. No schedule means nothing to compare with.
     */
    public static boolean clockOutAfterSchedule(Instant clockOut, LocalDate workDate, ZoneId zone,
            WorkSchedule schedule, Duration tolerance) {
        if (schedule == null) {
            return false;
        }
        LocalDate endDate = schedule.getEndTime().isAfter(schedule.getStartTime()) ? workDate : workDate.plusDays(1);
        Instant scheduledEnd = endDate.atTime(schedule.getEndTime()).atZone(zone).toInstant();
        return clockOut.isAfter(scheduledEnd.plus(tolerance));
    }
}
