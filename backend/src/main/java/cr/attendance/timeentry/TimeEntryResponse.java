package cr.attendance.timeentry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record TimeEntryResponse(
        Long id,
        UUID clientUuid,
        TimeEntryType type,
        Instant serverTime,
        LocalDate workDate,
        TimeEntryStatus status,
        Set<ReviewReason> reviewReasons,
        boolean syncedLate,
        Integer skewSeconds,
        Integer distanceMeters,
        Integer radiusMeters,
        Boolean insideGeofence,
        Integer lateMinutes,
        boolean hasPhoto,
        boolean replayed) {

    /** Inside means within the radius and with an acceptable accuracy; null when no position was sent. */
    static Boolean insideGeofence(TimeEntry entry) {
        if (entry.getLatitude() == null) {
            return null;
        }
        return !entry.getReviewReasons().contains(ReviewReason.OUTSIDE_GEOFENCE)
                && !entry.getReviewReasons().contains(ReviewReason.LOW_GPS_ACCURACY);
    }

    static TimeEntryResponse of(TimeEntry entry, Integer radiusMeters, boolean hasPhoto, boolean replayed) {
        return new TimeEntryResponse(entry.getId(), entry.getClientUuid(), entry.getType(), entry.getServerTime(),
                entry.getWorkDate(), entry.getStatus(), entry.getReviewReasons(), entry.isSyncedLate(),
                entry.getSkewSeconds(), entry.getDistanceMeters(), radiusMeters, insideGeofence(entry),
                entry.getLateMinutes(), hasPhoto, replayed);
    }
}
