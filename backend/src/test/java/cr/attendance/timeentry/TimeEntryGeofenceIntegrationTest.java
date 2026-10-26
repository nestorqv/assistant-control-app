package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestImages;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryGeofenceIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    // One degree of latitude is 111,195 m with the Earth radius used by GeoDistance.
    private static final double METERS_PER_DEGREE = 111_195.0;
    private static final double BRANCH_LAT = 9.9325;

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    /** Every case uses a fresh employee: only one CLOCK_IN per employee per day is allowed. */
    private JsonNode clockIn(Map<String, String> form) throws Exception {
        CheckInClient.Actor actor = client.actor();
        return client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));
    }

    private static List<String> reasons(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("reviewReasons").forEach(n -> out.add(n.asText()));
        return out;
    }

    private Map<String, Object> row(JsonNode body) {
        return jdbc.queryForMap("""
                select synced_late, skew_seconds, gps_accuracy_meters, review_reasons, status
                  from time_entry where id = ?
                """, body.get("id").asLong());
    }

    @Test
    void thirtyFiveMetersAwayIsValid() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.put("lat", String.valueOf(BRANCH_LAT + 35 / METERS_PER_DEGREE));
        JsonNode body = clockIn(form);
        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("distanceMeters").asInt()).isBetween(34, 36);
        assertThat(body.get("insideGeofence").asBoolean()).isTrue();
        assertThat(body.get("radiusMeters").asInt()).isEqualTo(100);
    }

    @Test
    void fourHundredTwentyMetersAwayNeedsReview() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.put("lat", String.valueOf(BRANCH_LAT + 420 / METERS_PER_DEGREE));
        JsonNode body = clockIn(form);
        assertThat(body.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(reasons(body)).containsExactly("OUTSIDE_GEOFENCE");
        assertThat(body.get("distanceMeters").asInt()).isBetween(419, 421);
        assertThat(body.get("insideGeofence").asBoolean()).isFalse();
    }

    @Test
    void noGpsNeedsReviewAndWritesNoGpsEvidence() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.remove("lat");
        form.remove("lng");
        form.remove("accuracy");
        CheckInClient.Actor actor = client.actor();
        JsonNode body = client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));
        assertThat(reasons(body)).containsExactly("NO_GPS");
        assertThat(body.get("insideGeofence").isNull()).isTrue();
        assertThat(body.get("distanceMeters").isNull()).isTrue();
        assertThat(client.evidenceCount(actor.employeeId())).isEqualTo(1); // PHOTO only
    }

    @Test
    void lowAccuracyNeedsReviewButGoodAccuracyDoesNot() throws Exception {
        Map<String, String> low = client.form("CLOCK_IN", clock.instant());
        low.put("accuracy", "500");
        JsonNode lowBody = clockIn(low);
        assertThat(reasons(lowBody)).containsExactly("LOW_GPS_ACCURACY");
        assertThat(((Number) row(lowBody).get("gps_accuracy_meters")).intValue()).isEqualTo(500);

        Map<String, String> good = client.form("CLOCK_IN", clock.instant());
        good.put("accuracy", "12");
        JsonNode goodBody = clockIn(good);
        assertThat(goodBody.get("status").asText()).isEqualTo("VALID");
        assertThat(((Number) row(goodBody).get("gps_accuracy_meters")).intValue()).isEqualTo(12);
    }

    @Test
    void aSlowPhoneThatSendsImmediatelyIsValid() throws Exception {
        Instant device = clock.instant().minus(Duration.ofMinutes(5));
        Map<String, String> form = client.form("CLOCK_IN", device);
        form.put("deviceSentAt", device.toString());
        JsonNode body = clockIn(form);
        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("syncedLate").asBoolean()).isFalse();
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(300);
    }

    @Test
    void aQueuedEntryIsFlaggedSyncedLate() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant().minus(Duration.ofMinutes(10)));
        form.put("deviceSentAt", clock.instant().toString());
        JsonNode body = clockIn(form);
        assertThat(body.get("syncedLate").asBoolean()).isTrue();
        assertThat(reasons(body)).containsExactly("SYNCED_LATE");
        assertThat(row(body).get("synced_late")).isEqualTo(true);
    }

    @Test
    void withoutDeviceSentAtTheSingleTimestampRuleApplies() throws Exception {
        JsonNode body = clockIn(client.form("CLOCK_IN", clock.instant().minus(Duration.ofMinutes(10))));
        assertThat(body.get("syncedLate").asBoolean()).isTrue();
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(600);
        assertThat(reasons(body)).containsExactly("SYNCED_LATE");
    }

    @Test
    void aDeviceClockInTheFutureIsFlagged() throws Exception {
        JsonNode body = clockIn(client.form("CLOCK_IN", clock.instant().plus(Duration.ofMinutes(10))));
        assertThat(reasons(body)).containsExactly("DEVICE_CLOCK_AHEAD");
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(-600);
    }

    @Test
    void aMinuteOfSkewIsFine() throws Exception {
        JsonNode body = clockIn(client.form("CLOCK_IN", clock.instant().minus(Duration.ofSeconds(60))));
        assertThat(body.get("status").asText()).isEqualTo("VALID");
    }

    @Test
    void theDeviceClockNeverChangesTheOfficialTime() throws Exception {
        Instant before = clock.instant();
        JsonNode body = clockIn(client.form("CLOCK_IN", before.minus(Duration.ofHours(3))));
        assertThat(Instant.parse(body.get("serverTime").asText())).isEqualTo(before);
    }
}
