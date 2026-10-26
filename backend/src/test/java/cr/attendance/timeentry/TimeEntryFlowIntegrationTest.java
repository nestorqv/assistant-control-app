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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryFlowIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Test
    void fullDayFromConsentToACompletedShift() throws Exception {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
        CheckInClient.Actor actor = client.actor();

        JsonNode today = client.json(client.get(actor, "/api/time-entries/today").andExpect(status().isOk()));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(today.get("blockedReason").isNull()).isTrue();

        Map<String, String> in = client.form("CLOCK_IN", clock.instant());
        JsonNode created = client.json(client.post(actor, in, TestImages.jpeg()).andExpect(status().isCreated()));
        assertThat(created.get("status").asText()).isEqualTo("VALID");
        assertThat(created.get("type").asText()).isEqualTo("CLOCK_IN");
        assertThat(created.get("replayed").asBoolean()).isFalse();
        assertThat(created.get("insideGeofence").asBoolean()).isTrue();
        assertThat(created.get("distanceMeters").asInt()).isZero();
        assertThat(created.get("hasPhoto").asBoolean()).isTrue();
        assertThat(created.get("reviewReasons")).isEmpty();
        long inId = created.get("id").asLong();

        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.evidenceCount(actor.employeeId())).isEqualTo(2); // PHOTO + GPS
        String key = client.photoKey(inId);
        assertThat(key).startsWith(actor.companyId() + "/");
        assertThat(client.storedFile(key)).exists();
        assertThat(client.filesOf(actor.companyId())).hasSize(1);

        today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        assertThat(today.get("inProgress").asBoolean()).isTrue();
        assertThat(today.get("clockIn").get("id").asLong()).isEqualTo(inId);

        clock.advance(Duration.ofHours(9));
        Map<String, String> out = client.form("CLOCK_OUT", clock.instant());
        JsonNode closed = client.json(client.post(actor, out, TestImages.png(300, 300))
                .andExpect(status().isCreated()));
        assertThat(closed.get("type").asText()).isEqualTo("CLOCK_OUT");
        assertThat(closed.get("lateMinutes").isNull()).isTrue();

        today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("NONE");
        assertThat(today.get("inProgress").asBoolean()).isFalse();
        assertThat(today.get("workedMinutes").asLong()).isEqualTo(540);
        assertThat(today.get("clockOut").get("id").asLong()).isEqualTo(closed.get("id").asLong());

        JsonNode third = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isConflict()));
        assertThat(third.get("code").asText()).isEqualTo("SHIFT_ALREADY_COMPLETED");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(2);
        assertThat(client.filesOf(actor.companyId())).hasSize(2);
    }

    @Test
    void storedPhotosAreReencodedJpegsWithoutExif() throws Exception {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
        CheckInClient.Actor actor = client.actor();
        JsonNode created = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()),
                TestImages.jpegWithExif()).andExpect(status().isCreated()));

        byte[] stored = java.nio.file.Files.readAllBytes(client.storedFile(client.photoKey(created.get("id").asLong())));
        assertThat(stored[0]).isEqualTo((byte) 0xFF);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(TestImages.EXIF_MARKER).doesNotContain("Exif");
    }
}
