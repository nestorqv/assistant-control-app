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
}
