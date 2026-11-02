package cr.attendance.admin;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryResponse;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Instant;
import java.util.Set;

/** One entry as lists see it. No coordinates, no client uuid, no storage key. */
public record AdminEntrySummary(
        Long id,
        TimeEntryType type,
        Instant effectiveTime,
        Instant serverTime,
        TimeEntryStatus status,
        Set<ReviewReason> reviewReasons,
        TimeEntrySource source,
        Integer distanceMeters,
        Boolean insideGeofence,
        boolean hasPhoto,
        boolean hasGps,
        boolean adjusted) {

    public static AdminEntrySummary of(TimeEntry e, boolean hasPhoto, boolean adjusted) {
        return new AdminEntrySummary(e.getId(), e.getType(), e.getEffectiveTime(), e.getServerTime(), e.getStatus(),
                e.getReviewReasons(), e.getSource(), e.getDistanceMeters(), TimeEntryResponse.insideGeofence(e),
                hasPhoto, e.getLatitude() != null, adjusted);
    }
}
