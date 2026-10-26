package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReviewPolicyTest {

    private static final Instant SERVER = Instant.parse("2026-10-22T14:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(2);

    private static ReviewPolicy.Outcome eval(Double lat, Double lng, Double accuracy, Integer distance,
            Instant deviceTime, Instant deviceSentAt, boolean tooLong) {
        return ReviewPolicy.evaluate(new ReviewPolicy.Input(lat, lng, accuracy, distance, 100, SERVER, deviceTime,
                deviceSentAt, tooLong), TOLERANCE, 100);
    }

    private static ReviewPolicy.Outcome good(Instant deviceTime, Instant deviceSentAt) {
        return eval(9.9, -84.0, 10.0, 35, deviceTime, deviceSentAt, false);
    }

    @Test
    void insideWithGoodAccuracyAndFreshClockIsValid() {
        ReviewPolicy.Outcome o = good(SERVER.minusSeconds(30), null);
        assertThat(o.status()).isEqualTo(TimeEntryStatus.VALID);
        assertThat(o.reasons()).isEmpty();
        assertThat(o.insideGeofence()).isTrue();
        assertThat(o.syncedLate()).isFalse();
    }

    @Test
    void noGpsAlone() {
        ReviewPolicy.Outcome o = eval(null, null, null, null, SERVER, null, false);
        assertThat(o.reasons()).containsExactly(ReviewReason.NO_GPS);
        assertThat(o.status()).isEqualTo(TimeEntryStatus.PENDING_REVIEW);
        assertThat(o.insideGeofence()).isNull();
    }

    @Test
    void outsideGeofenceAloneAndBoundary() {
        assertThat(eval(9.9, -84.0, 10.0, 420, SERVER, null, false).reasons())
                .containsExactly(ReviewReason.OUTSIDE_GEOFENCE);
        assertThat(eval(9.9, -84.0, 10.0, 100, SERVER, null, false).reasons()).isEmpty();
        assertThat(eval(9.9, -84.0, 10.0, 101, SERVER, null, false).reasons())
                .containsExactly(ReviewReason.OUTSIDE_GEOFENCE);
    }

    @Test
    void accuracyBoundary() {
        assertThat(eval(9.9, -84.0, 100.0, 35, SERVER, null, false).reasons()).isEmpty();
        ReviewPolicy.Outcome o = eval(9.9, -84.0, 101.0, 35, SERVER, null, false);
        assertThat(o.reasons()).containsExactly(ReviewReason.LOW_GPS_ACCURACY);
        assertThat(o.insideGeofence()).isFalse();
    }

    @Test
    void toleranceBoundariesWithoutDeviceSentAt() {
        assertThat(good(SERVER.minusSeconds(120), null).reasons()).isEmpty();
        assertThat(good(SERVER.minusSeconds(121), null).reasons()).containsExactly(ReviewReason.SYNCED_LATE);
        assertThat(good(SERVER.plusSeconds(120), null).reasons()).isEmpty();
        assertThat(good(SERVER.plusSeconds(121), null).reasons()).containsExactly(ReviewReason.DEVICE_CLOCK_AHEAD);
    }

    @Test
    void fallbackRuleUsesTheSingleTimestamp() {
        ReviewPolicy.Outcome o = good(SERVER.minusSeconds(600), null);
        assertThat(o.syncedLate()).isTrue();
        assertThat(o.skewSeconds()).isEqualTo(600);
    }

    @Test
    void slowPhoneSentImmediatelyIsNotLate() {
        Instant device = SERVER.minusSeconds(300);
        ReviewPolicy.Outcome o = good(device, device);
        assertThat(o.syncedLate()).isFalse();
        assertThat(o.reasons()).isEmpty();
        assertThat(o.skewSeconds()).isEqualTo(300);
    }

    @Test
    void queueDelayDecidesSyncedLateWhateverTheSkew() {
        // Sent 10 minutes after it was taken; the phone clock is also 5 minutes slow.
        Instant taken = SERVER.minusSeconds(900);
        Instant sent = SERVER.minusSeconds(300);
        ReviewPolicy.Outcome o = good(taken, sent);
        assertThat(o.syncedLate()).isTrue();
        assertThat(o.reasons()).containsExactly(ReviewReason.SYNCED_LATE);
        assertThat(o.skewSeconds()).isEqualTo(300);
    }

    @Test
    void phoneFiveMinutesAheadIsFlagged() {
        Instant device = SERVER.plusSeconds(300);
        ReviewPolicy.Outcome o = good(device, device);
        assertThat(o.reasons()).containsExactly(ReviewReason.DEVICE_CLOCK_AHEAD);
        assertThat(o.skewSeconds()).isEqualTo(-300);
    }

    @Test
    void deviceSentAtEarlierThanDeviceTimeIsIgnored() {
        Instant taken = SERVER.minusSeconds(600);
        ReviewPolicy.Outcome o = good(taken, taken.minusSeconds(60));
        // Fallback rule: 600 s behind the server.
        assertThat(o.syncedLate()).isTrue();
        assertThat(o.skewSeconds()).isEqualTo(600);
    }

    @Test
    void shiftTooLongAddsItsReason() {
        ReviewPolicy.Outcome o = eval(9.9, -84.0, 10.0, 35, SERVER, null, true);
        assertThat(o.reasons()).containsExactly(ReviewReason.SHIFT_TOO_LONG);
        assertThat(o.status()).isEqualTo(TimeEntryStatus.PENDING_REVIEW);
    }

    @Test
    void reasonsCombine() {
        ReviewPolicy.Outcome o = eval(9.9, -84.0, 500.0, 420, SERVER.minusSeconds(900), null, true);
        assertThat(o.reasons()).isEqualTo(EnumSet.of(ReviewReason.OUTSIDE_GEOFENCE, ReviewReason.LOW_GPS_ACCURACY,
                ReviewReason.SYNCED_LATE, ReviewReason.SHIFT_TOO_LONG));
    }

    @Test
    void aHundredYearGapDoesNotOverflowTheSkew() {
        ReviewPolicy.Outcome o = good(Instant.parse("2099-12-31T00:00:00Z"), null);
        assertThat(o.skewSeconds()).isLessThan(0);
    }
}
