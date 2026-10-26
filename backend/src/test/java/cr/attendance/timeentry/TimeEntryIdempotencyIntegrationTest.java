package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryIdempotencyIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    @Test
    void theSameRequestTwiceCreatesOneRowAndOneFile() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());

        JsonNode first = client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));
        JsonNode second = client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isOk()));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(first.get("replayed").asBoolean()).isFalse();
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void aReplayWithDifferentFieldsReturnsTheOriginalValues() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        JsonNode first = client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));

        Map<String, String> changed = new LinkedHashMap<>(form);
        changed.put("lat", "10.5");
        changed.put("lng", "-83.0");
        JsonNode replay = client.json(client.post(actor, changed, TestImages.png(100, 100))
                .andExpect(status().isOk()));

        assertThat(replay.get("status").asText()).isEqualTo(first.get("status").asText());
        assertThat(replay.get("distanceMeters").asInt()).isEqualTo(first.get("distanceMeters").asInt());
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void theSameUuidWithAnotherTypeIsAConflict() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated());

        Map<String, String> other = new LinkedHashMap<>(form);
        other.put("type", "CLOCK_OUT");
        JsonNode body = client.json(client.post(actor, other, TestImages.jpeg()).andExpect(status().isConflict()));
        assertThat(body.get("code").asText()).isEqualTo("CLIENT_UUID_CONFLICT");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
    }

    @Test
    void anotherEmployeesUuidIsAConflictWithoutLeakingTheirData() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor first = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor second = client.actor(tenant, "EMPLOYEE", true);
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        JsonNode original = client.json(client.post(first, form, TestImages.jpeg()).andExpect(status().isCreated()));

        var result = client.post(second, form, TestImages.jpeg()).andExpect(status().isConflict());
        String raw = result.andReturn().getResponse().getContentAsString();
        assertThat(client.json(result).get("code").asText()).isEqualTo("CLIENT_UUID_CONFLICT");
        assertThat(raw).doesNotContain(String.valueOf(original.get("id").asLong()))
                .doesNotContain(String.valueOf(first.employeeId())).doesNotContain("distanceMeters");
        assertThat(client.entryCount(second.employeeId())).isZero();
    }

    @Test
    void theSameUuidInAnotherCompanyIsASeparateEntry() throws Exception {
        CheckInClient.Actor a = client.actor();
        CheckInClient.Actor b = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        client.post(a, form, TestImages.jpeg()).andExpect(status().isCreated());
        client.post(b, form, TestImages.jpeg()).andExpect(status().isCreated());
        assertThat(client.entryCount(a.employeeId())).isEqualTo(1);
        assertThat(client.entryCount(b.employeeId())).isEqualTo(1);
    }

    @Test
    void aClockInReplayedAfterTheClockOutStillReturnsTheOriginal() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> in = client.form("CLOCK_IN", clock.instant());
        JsonNode first = client.json(client.post(actor, in, TestImages.jpeg()).andExpect(status().isCreated()));
        clock.advance(Duration.ofHours(2));
        client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated());

        JsonNode replay = client.json(client.post(actor, in, TestImages.jpeg()).andExpect(status().isOk()));
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(replay.get("type").asText()).isEqualTo("CLOCK_IN");
    }

    @Test
    void aReplayWithoutAPhotoReturnsTheOriginalWithItsPhoto() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        JsonNode first = client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));

        JsonNode replay = client.json(client.post(actor, form, null).andExpect(status().isOk()));
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("hasPhoto").asBoolean()).isTrue();
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void aPhotolessReplayFromAnotherEmployeeIsAConflictNotPhotoRequired() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor first = client.actor(tenant, "EMPLOYEE", true);
        CheckInClient.Actor second = client.actor(tenant, "EMPLOYEE", true);
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        client.post(first, form, TestImages.jpeg()).andExpect(status().isCreated());

        JsonNode body = client.json(client.post(second, form, null).andExpect(status().isConflict()));
        assertThat(body.get("code").asText()).isEqualTo("CLIENT_UUID_CONFLICT");
    }
}
