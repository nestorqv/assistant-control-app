package cr.attendance.timeentry;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Decides VALID or PENDING_REVIEW. The device clock is only a review signal, never the official time. */
public final class ReviewPolicy {

    private ReviewPolicy() {
    }

    public record Input(Double lat, Double lng, Double accuracy, Integer distanceMeters, int radiusMeters,
            Instant serverTime, Instant deviceTime, Instant deviceSentAt, boolean shiftTooLong) {
    }

    public record Outcome(Set<ReviewReason> reasons, boolean syncedLate, int skewSeconds,
            TimeEntryStatus status, Boolean insideGeofence) {
    }

    public static Outcome evaluate(Input in, Duration tolerance, int maxGpsAccuracyMeters) {
        Set<ReviewReason> reasons = EnumSet.noneOf(ReviewReason.class);

        boolean hasGps = in.lat() != null && in.lng() != null;
        Boolean inside = null;
        if (!hasGps) {
            reasons.add(ReviewReason.NO_GPS);
        } else {
            boolean outside = in.distanceMeters() > in.radiusMeters();
            boolean lowAccuracy = in.accuracy() != null && in.accuracy() > maxGpsAccuracyMeters;
            if (outside) {
                reasons.add(ReviewReason.OUTSIDE_GEOFENCE);
            }
            if (lowAccuracy) {
                reasons.add(ReviewReason.LOW_GPS_ACCURACY);
            }
            inside = !outside && !lowAccuracy;
        }

        // With a usable deviceSentAt the queue delay is measured on one clock (the phone), so a wrong
        // phone clock cancels out. A deviceSentAt earlier than deviceTime is a client bug: ignore it.
        boolean usableSentAt = in.deviceSentAt() != null && !in.deviceSentAt().isBefore(in.deviceTime());
        Duration skew;
        boolean syncedLate;
        if (usableSentAt) {
            skew = Duration.between(in.deviceSentAt(), in.serverTime());
            syncedLate = Duration.between(in.deviceTime(), in.deviceSentAt()).compareTo(tolerance) > 0;
        } else {
            skew = Duration.between(in.deviceTime(), in.serverTime());
            syncedLate = skew.compareTo(tolerance) > 0;
        }
        if (syncedLate) {
            reasons.add(ReviewReason.SYNCED_LATE);
        }
        if (skew.compareTo(tolerance.negated()) < 0) {
            reasons.add(ReviewReason.DEVICE_CLOCK_AHEAD);
        }
        if (in.shiftTooLong()) {
            reasons.add(ReviewReason.SHIFT_TOO_LONG);
        }

        TimeEntryStatus status = reasons.isEmpty() ? TimeEntryStatus.VALID : TimeEntryStatus.PENDING_REVIEW;
        // The instants are range checked (2000-2100), but a 100 year gap does not fit in int seconds.
        int skewSeconds = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, skew.toSeconds()));
        return new Outcome(reasons, syncedLate, skewSeconds, status, inside);
    }
}
