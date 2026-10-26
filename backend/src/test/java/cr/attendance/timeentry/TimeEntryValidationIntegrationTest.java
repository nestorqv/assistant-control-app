package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryValidationIntegrationTest {

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

    private void assertError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status));
        assertThat(client.json(result).get("code").asText()).isEqualTo(code);
    }

    private void assertNothingWritten(CheckInClient.Actor actor) {
        assertThat(client.entryCount(actor.employeeId())).isZero();
        assertThat(client.evidenceCount(actor.employeeId())).isZero();
        assertThat(client.filesOf(actor.companyId())).isEmpty();
    }

    @Test
    void clockOutFirstAndDoubleClockIn() throws Exception {
        CheckInClient.Actor actor = client.actor();
        assertError(client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg()), 409,
                "NOT_CLOCKED_IN");
        assertNothingWritten(actor);

        client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).andExpect(status().isCreated());
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()), 409,
                "ALREADY_CLOCKED_IN");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
    }

    @Test
    void withoutConsentNothingIsWritten() throws Exception {
        CheckInClient.Actor actor = client.actor(data.tenant(), "EMPLOYEE", false);
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()), 403,
                "CONSENT_REQUIRED");
        assertNothingWritten(actor);
    }

    @Test
    void consentAndBranchAreCheckedBeforeThePhotoIsDecoded() throws Exception {
        CheckInClient.Actor actor = client.actor(data.tenant(), "EMPLOYEE", false);
        // An invalid photo from someone who cannot clock in anyway still reports the cheap failure first.
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.text()), 403,
                "CONSENT_REQUIRED");
        assertNothingWritten(actor);
    }

    @Test
    void anAdminWithoutABranchCannotClockInButOneWithABranchCan() throws Exception {
        TestData.Tenant tenant = data.tenant();
        CheckInClient.Actor admin = client.actor(tenant, "ADMIN", true);
        assertError(client.post(admin, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()), 403,
                "NO_BRANCH_ASSIGNED");
        assertNothingWritten(admin);

        CheckInClient.Actor branchAdmin = client.adminWithBranch(tenant);
        client.post(branchAdmin, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated());
    }

    @Test
    void malformedFieldsGive400WithoutEchoingTheValue() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, Consumer<Map<String, String>>> cases = new LinkedHashMap<>();
        cases.put("missing type", f -> f.remove("type"));
        cases.put("lowercase type", f -> f.put("type", "clock_in"));
        cases.put("bad uuid", f -> f.put("clientUuid", "not-a-uuid-zzz"));
        cases.put("nil uuid", f -> f.put("clientUuid", "00000000-0000-0000-0000-000000000000"));
        cases.put("missing uuid", f -> f.remove("clientUuid"));
        cases.put("missing deviceTime", f -> f.remove("deviceTime"));
        cases.put("bad deviceTime", f -> f.put("deviceTime", "yesterday-ish"));
        cases.put("year 1970", f -> f.put("deviceTime", "1970-01-01T00:00:00Z"));
        cases.put("year 2100", f -> f.put("deviceTime", "2100-01-01T00:00:00Z"));
        cases.put("lat 91", f -> f.put("lat", "91"));
        cases.put("lng -181", f -> f.put("lng", "-181"));
        cases.put("lat NaN", f -> f.put("lat", "NaN"));
        cases.put("lat Infinity", f -> f.put("lat", "Infinity"));
        cases.put("lng Infinity", f -> f.put("lng", "-Infinity"));
        cases.put("lat without lng", f -> f.remove("lng"));
        cases.put("lng without lat", f -> f.remove("lat"));
        cases.put("comma decimal", f -> f.put("lat", "9,93"));
        cases.put("accuracy without position", f -> {
            f.remove("lat");
            f.remove("lng");
        });
        cases.put("negative accuracy", f -> f.put("accuracy", "-1"));
        cases.put("accuracy NaN", f -> f.put("accuracy", "NaN"));
        cases.put("accuracy too large", f -> f.put("accuracy", "100001"));
        cases.put("deviceSentAt 1970", f -> f.put("deviceSentAt", "1970-01-01T00:00:00Z"));
        cases.put("bad deviceSentAt", f -> f.put("deviceSentAt", "later"));

        for (Map.Entry<String, Consumer<Map<String, String>>> c : cases.entrySet()) {
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            c.getValue().accept(form);
            ResultActions result = client.post(actor, form, TestImages.jpeg());
            result.andExpect(status().isBadRequest());
            String raw = result.andReturn().getResponse().getContentAsString();
            assertThat(client.json(result).get("code").asText()).as(c.getKey()).isEqualTo("INVALID_REQUEST");
            for (String echoed : new String[] {"not-a-uuid-zzz", "yesterday-ish", "9,93", "later", "1970"}) {
                assertThat(raw).as(c.getKey()).doesNotContain(echoed);
            }
        }
        assertNothingWritten(actor);
    }

    @Test
    void aJsonBodyGets415() throws Exception {
        CheckInClient.Actor actor = client.actor();
        assertError(client.perform(post("/api/time-entries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CLOCK_IN\"}").header("Authorization", "Bearer " + actor.token())), 415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void aNewEntryWithoutAPhotoIsRejectedForBothTypes() throws Exception {
        CheckInClient.Actor actor = client.actor();
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), null), 400, "PHOTO_REQUIRED");
        assertError(client.post(actor, client.form("CLOCK_OUT", clock.instant()), null), 400, "PHOTO_REQUIRED");
        assertNothingWritten(actor);
    }

    @Test
    void missingPhotoWinsOverMissingConsent() throws Exception {
        CheckInClient.Actor actor = client.actor(data.tenant(), "EMPLOYEE", false);
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), null), 400, "PHOTO_REQUIRED");
        assertNothingWritten(actor);
    }

    @Test
    void badPhotosGive400InvalidPhoto() throws Exception {
        CheckInClient.Actor actor = client.actor();
        for (byte[] bad : new byte[][] {TestImages.text(), TestImages.truncatedJpeg(), new byte[0],
            TestImages.pngHeaderOnly(50_000, 50_000)}) {
            assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), bad), 400, "INVALID_PHOTO");
        }
        assertNothingWritten(actor);
    }

    @Test
    void aPhotoOverTwoMegabytesGives413() throws Exception {
        CheckInClient.Actor actor = client.actor();
        byte[] tooBig = TestImages.jpegOfSize(2 * 1024 * 1024 + 1);
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), tooBig), 413, "PAYLOAD_TOO_LARGE");
        assertNothingWritten(actor);
    }

    @Test
    void errorTextsFollowAcceptLanguage() throws Exception {
        CheckInClient.Actor actor = client.actor();
        JsonNode en = client.json(client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), null).header("Accept-Language", "en"))
                .andExpect(status().isBadRequest()));
        JsonNode es = client.json(client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), null).header("Accept-Language", "es"))
                .andExpect(status().isBadRequest()));
        assertThat(en.get("code").asText()).isEqualTo("PHOTO_REQUIRED");
        assertThat(en.get("title").asText()).isEqualTo("Photo required");
        assertThat(es.get("title").asText()).isEqualTo("Foto requerida");

        client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).andExpect(status().isCreated());
        JsonNode conflictEn = client.json(client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).header("Accept-Language", "en"))
                .andExpect(status().isConflict()));
        JsonNode conflictEs = client.json(client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).header("Accept-Language", "es"))
                .andExpect(status().isConflict()));
        assertThat(conflictEn.get("title").asText()).isEqualTo("Already clocked in");
        assertThat(conflictEs.get("title").asText()).isEqualTo("Entrada ya registrada");
    }

    @Test
    void withoutATokenThePostIsUnauthorized() throws Exception {
        client.post(null, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isUnauthorized());
    }
}
