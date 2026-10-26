package cr.attendance.timeentry;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Multipart form of POST /api/time-entries. The photo is not validated here: its presence is checked by
 * the service after the idempotent-replay lookup, because a replay does not need to send it again.
 */
public record TimeEntryForm(
        @NotNull TimeEntryType type,
        @NotNull UUID clientUuid,
        @NotNull Instant deviceTime,
        Double lat,
        Double lng,
        Double accuracy,
        Instant deviceSentAt,
        MultipartFile photo) {

    private static final Instant MIN_INSTANT = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX_INSTANT = Instant.parse("2100-01-01T00:00:00Z");
    private static final double MAX_ACCURACY = 100_000;

    /** lat and lng come together, are finite and in range. Accuracy needs a position. */
    @AssertTrue
    public boolean isLocationValid() {
        if ((lat == null) != (lng == null)) {
            return false;
        }
        if (lat != null && (!Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
            return false;
        }
        if (accuracy != null) {
            return lat != null && Double.isFinite(accuracy) && accuracy >= 0 && accuracy <= MAX_ACCURACY;
        }
        return true;
    }

    @AssertTrue
    public boolean isDeviceTimeInRange() {
        return inRange(deviceTime) && inRange(deviceSentAt);
    }

    @AssertTrue
    public boolean isClientUuidNotNil() {
        return clientUuid == null || clientUuid.getMostSignificantBits() != 0
                || clientUuid.getLeastSignificantBits() != 0;
    }

    private static boolean inRange(Instant instant) {
        return instant == null || (!instant.isBefore(MIN_INSTANT) && instant.isBefore(MAX_INSTANT));
    }

    // Coordinates are personal data (Ley 8968): keep them out of logs.
    @Override
    public String toString() {
        return "TimeEntryForm[type=" + type + ", clientUuid=" + clientUuid + ", location="
                + (lat != null ? "***" : "none") + "]";
    }
}
