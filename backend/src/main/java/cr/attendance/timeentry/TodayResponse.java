package cr.attendance.timeentry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/** State that drives the single clock button. */
public record TodayResponse(
        LocalDate workDate,
        NextAction nextAction,
        String blockedReason,
        EntrySummary clockIn,
        EntrySummary clockOut,
        Long workedMinutes,
        boolean inProgress,
        boolean missingClockOut,
        LocalDate forgottenWorkDate,
        ScheduleInfo schedule,
        BranchInfo branch,
        Instant serverTime,
        String timeZone,
        boolean consentPending) {

    public record EntrySummary(Long id, Instant serverTime, TimeEntryStatus status, Set<ReviewReason> reviewReasons,
            Integer distanceMeters, Boolean insideGeofence, Integer lateMinutes, boolean hasPhoto) {
    }

    public record ScheduleInfo(LocalTime startTime, LocalTime endTime, int graceMinutes, boolean overnight) {
    }

    // Coordinates are not exposed.
    public record BranchInfo(String name, int radiusMeters) {
    }
}
