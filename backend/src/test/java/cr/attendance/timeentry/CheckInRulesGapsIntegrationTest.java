package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.JwtTestTokens;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tester additions for Stage 2: check order with combined errors, replay edge cases, sequence and 16 h
 * boundaries, review-policy boundaries through the API, other company zones, token handling.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
class CheckInRulesGapsIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final double BRANCH_LAT = 9.9325;
    // Meters per degree of latitude with the mean Earth radius used by GeoDistance (6,371,008.8 m).
    private static final double METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180.0;

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTestTokens tokens;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    private void assertError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status));
        assertThat(client.json(result).get("code").asText()).isEqualTo(code);
    }

    private JsonNode created(CheckInClient.Actor actor, Map<String, String> form) throws Exception {
        return client.json(client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated()));
    }

    private static List<String> reasons(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("reviewReasons").forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode today(CheckInClient.Actor actor) throws Exception {
        return client.json(client.get(actor, "/api/time-entries/today").andExpect(status().isOk()));
    }

    // ---------------------------------------------------------------- check order with combined errors

    @Test
    void aMalformedFormBeatsAMissingPhoto() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.remove("type");
        assertError(client.post(actor, form, null), 400, "INVALID_REQUEST");
    }

    @Test
    void missingConsentBeatsAnOversizedOrBrokenPhoto() throws Exception {
        CheckInClient.Actor actor = client.actor(data.tenant(), "EMPLOYEE", false);
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()),
                TestImages.jpegOfSize(2 * 1024 * 1024 + 1)), 403, "CONSENT_REQUIRED");
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), new byte[0]), 403,
                "CONSENT_REQUIRED");
        assertThat(client.entryCount(actor.employeeId())).isZero();
        assertThat(client.filesOf(actor.companyId())).isEmpty();
    }

    @Test
    void consentIsCheckedBeforeTheBranch() throws Exception {
        CheckInClient.Actor admin = client.actor(data.tenant(), "ADMIN", false); // no consent and no branch
        assertError(client.post(admin, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()), 403,
                "CONSENT_REQUIRED");
    }

    @Test
    void noBranchBeatsABrokenPhoto() throws Exception {
        CheckInClient.Actor admin = client.actor(data.tenant(), "ADMIN", true);
        assertError(client.post(admin, client.form("CLOCK_IN", clock.instant()), TestImages.text()), 403,
                "NO_BRANCH_ASSIGNED");
        assertThat(client.filesOf(admin.companyId())).isEmpty();
    }

    @Test
    void aBrokenPhotoBeatsASequenceViolation() throws Exception {
        CheckInClient.Actor actor = client.actor();
        // Nothing is open: a CLOCK_OUT would be 409 NOT_CLOCKED_IN, but the photo is validated first.
        assertError(client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.text()), 400,
                "INVALID_PHOTO");
        created(actor, client.form("CLOCK_IN", clock.instant()));
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.text()), 400,
                "INVALID_PHOTO");
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()), 409,
                "ALREADY_CLOCKED_IN");
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
    }

    @Test
    void anOversizedPhotoIs413EvenWhenItIsNotAnImage() throws Exception {
        CheckInClient.Actor actor = client.actor();
        byte[] bigText = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(bigText, (byte) 'A');
        assertError(client.post(actor, client.form("CLOCK_IN", clock.instant()), bigText), 413,
                "PAYLOAD_TOO_LARGE");
    }

    // ---------------------------------------------------------------- idempotency edge cases

    @Test
    void aReplayIsNotValidatedAgainstItsPhotoPart() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        JsonNode first = created(actor, form);

        for (byte[] junk : new byte[][] {TestImages.text(), new byte[0],
            TestImages.jpegOfSize(2 * 1024 * 1024 + 1)}) {
            JsonNode replay = client.json(client.post(actor, form, junk).andExpect(status().isOk()));
            assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
            assertThat(replay.get("replayed").asBoolean()).isTrue();
        }
        assertThat(client.filesOf(actor.companyId())).hasSize(1);
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
    }

    @Test
    void aPhotolessReplayInAnotherCompanyFindsNothingAndSaysPhotoRequired() throws Exception {
        CheckInClient.Actor a = client.actor();
        CheckInClient.Actor b = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        created(a, form);
        // Same uuid, other tenant, no photo: the key is per tenant, so B never sees A's entry.
        JsonNode body = client.json(client.post(b, form, null).andExpect(status().isBadRequest()));
        assertThat(body.get("code").asText()).isEqualTo("PHOTO_REQUIRED");
        assertThat(client.entryCount(b.employeeId())).isZero();
    }

    @Test
    void aClockOutReplayAfterTheDayIsDoneStillReturnsTheClockOut() throws Exception {
        CheckInClient.Actor actor = client.actor();
        created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(8));
        Map<String, String> out = client.form("CLOCK_OUT", clock.instant());
        JsonNode first = created(actor, out);

        clock.advance(Duration.ofHours(30)); // the next day
        JsonNode replay = client.json(client.post(actor, out, null).andExpect(status().isOk()));
        assertThat(replay.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(replay.get("hasPhoto").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- sequence rules

    @Test
    void aSecondClockOutIsNotClockedIn() throws Exception {
        CheckInClient.Actor actor = client.actor();
        created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(1));
        created(actor, client.form("CLOCK_OUT", clock.instant()));
        assertError(client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg()), 409,
                "NOT_CLOCKED_IN");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(2);
        assertThat(client.filesOf(actor.companyId())).hasSize(2);
    }

    @Test
    void theSixteenHourWindowIsExclusiveAtTheEdge() throws Exception {
        CheckInClient.Actor justInside = client.actor();
        CheckInClient.Actor atTheEdge = client.actor();
        created(justInside, client.form("CLOCK_IN", clock.instant()));
        created(atTheEdge, client.form("CLOCK_IN", clock.instant()));

        clock.advance(Duration.ofHours(15).plusMinutes(59).plusSeconds(59));
        JsonNode fresh = created(justInside, client.form("CLOCK_OUT", clock.instant()));
        assertThat(reasons(fresh)).doesNotContain("SHIFT_TOO_LONG");

        clock.advance(Duration.ofSeconds(1)); // exactly 16 h after the CLOCK_IN
        JsonNode stale = created(atTheEdge, client.form("CLOCK_OUT", clock.instant()));
        assertThat(reasons(stale)).contains("SHIFT_TOO_LONG");
        assertThat(stale.get("status").asText()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void aForgottenShiftIsNotTheTargetOfTodaysClockOut() throws Exception {
        CheckInClient.Actor actor = client.actor();
        JsonNode monday = created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(24));
        JsonNode tuesdayIn = created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(9));
        JsonNode tuesdayOut = created(actor, client.form("CLOCK_OUT", clock.instant()));

        assertThat(tuesdayIn.get("workDate").asText()).isNotEqualTo(monday.get("workDate").asText());
        assertThat(tuesdayOut.get("workDate").asText()).isEqualTo(tuesdayIn.get("workDate").asText());
        assertThat(reasons(tuesdayOut)).doesNotContain("SHIFT_TOO_LONG");
        assertThat(tuesdayOut.get("status").asText()).isEqualTo("VALID");
    }

    @Test
    void aClockOutBeyondTheLookBackWindowIsNotClockedInButTheEmployeeIsNotStuck() throws Exception {
        CheckInClient.Actor actor = client.actor();
        created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(24 * 5)); // forgotten for five days
        assertError(client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg()), 409,
                "NOT_CLOCKED_IN");
        JsonNode state = today(actor);
        assertThat(state.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        created(actor, client.form("CLOCK_IN", clock.instant()));
    }

    @Test
    void afterACompletedDayTheNextLocalDayOffersClockInAgain() throws Exception {
        CheckInClient.Actor actor = client.actor();
        created(actor, client.form("CLOCK_IN", clock.instant()));
        clock.advance(Duration.ofHours(9));
        created(actor, client.form("CLOCK_OUT", clock.instant()));
        assertThat(today(actor).get("nextAction").asText()).isEqualTo("NONE");

        clock.advanceToNext(CR, LocalTime.of(8, 0));
        JsonNode next = today(actor);
        assertThat(next.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(next.get("clockIn").isNull()).isTrue();
        assertThat(next.get("clockOut").isNull()).isTrue();
        assertThat(next.get("workedMinutes").isNull()).isTrue();
        assertThat(next.get("workDate").asText()).isEqualTo(clock.instant().atZone(CR).toLocalDate().toString());
        created(actor, client.form("CLOCK_IN", clock.instant()));
    }

    // ---------------------------------------------------------------- review policy through the API

    private String latAt(double metersNorth) {
        return String.valueOf(BRANCH_LAT + metersNorth / METERS_PER_DEGREE);
    }

    @Test
    void theGeofenceRadiusBoundaryIsInclusive() throws Exception {
        Map<String, String> atRadius = client.form("CLOCK_IN", clock.instant());
        atRadius.put("lat", latAt(100));
        JsonNode inside = created(client.actor(), atRadius);
        assertThat(inside.get("distanceMeters").asInt()).isEqualTo(100);
        assertThat(inside.get("status").asText()).isEqualTo("VALID");
        assertThat(inside.get("insideGeofence").asBoolean()).isTrue();

        Map<String, String> beyond = client.form("CLOCK_IN", clock.instant());
        beyond.put("lat", latAt(101));
        JsonNode outside = created(client.actor(), beyond);
        assertThat(outside.get("distanceMeters").asInt()).isEqualTo(101);
        assertThat(reasons(outside)).containsExactly("OUTSIDE_GEOFENCE");
        assertThat(outside.get("insideGeofence").asBoolean()).isFalse();
    }

    @Test
    void theGpsAccuracyBoundaryIsAHundredMeters() throws Exception {
        for (String ok : new String[] {"0", "100"}) {
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            form.put("accuracy", ok);
            assertThat(created(client.actor(), form).get("status").asText()).as("accuracy " + ok).isEqualTo("VALID");
        }
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.put("accuracy", "100.5");
        JsonNode low = created(client.actor(), form);
        assertThat(reasons(low)).containsExactly("LOW_GPS_ACCURACY");
        assertThat(low.get("insideGeofence").asBoolean()).isFalse();
    }

    @Test
    void positionsAtTheEdgesOfTheValidRangeAreAcceptedAndJustBeyondAreNot() throws Exception {
        for (String[] pos : new String[][] {{"90", "180"}, {"-90", "-180"}, {"0", "0"}}) {
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            form.put("lat", pos[0]);
            form.put("lng", pos[1]);
            JsonNode body = created(client.actor(), form);
            assertThat(reasons(body)).as(pos[0] + "," + pos[1]).containsExactly("OUTSIDE_GEOFENCE");
            assertThat(body.get("distanceMeters").asInt()).isPositive();
        }
        CheckInClient.Actor actor = client.actor();
        for (String[] pos : new String[][] {{"90.0000001", "0"}, {"0", "180.0000001"}, {"-90.1", "0"}}) {
            Map<String, String> form = client.form("CLOCK_IN", clock.instant());
            form.put("lat", pos[0]);
            form.put("lng", pos[1]);
            assertError(client.post(actor, form, TestImages.jpeg()), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void deviceTimeWithAnOffsetIsTheSameInstantAndAZonelessOneIsRejected() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.put("deviceTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(clock.instant().atOffset(ZoneOffset.ofHours(2))));
        JsonNode body = created(client.actor(), form);
        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("skewSeconds").asInt()).isZero();

        CheckInClient.Actor actor = client.actor();
        for (String bad : new String[] {"2026-10-22T13:58:00", "2026-10-22", "tomorrow"}) {
            Map<String, String> badForm = client.form("CLOCK_IN", clock.instant());
            badForm.put("deviceTime", bad);
            assertError(client.post(actor, badForm, TestImages.jpeg()), 400, "INVALID_REQUEST");
        }
        assertThat(client.entryCount(actor.employeeId())).isZero();
    }

    @Test
    void aDeviceSentAtEarlierThanDeviceTimeIsIgnoredNotRejected() throws Exception {
        Map<String, String> form = client.form("CLOCK_IN", clock.instant().minus(Duration.ofMinutes(10)));
        form.put("deviceSentAt", clock.instant().minus(Duration.ofMinutes(20)).toString());
        JsonNode body = created(client.actor(), form);
        // Fallback rule: skew = server - deviceTime = 600 s, which is late.
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(600);
        assertThat(reasons(body)).containsExactly("SYNCED_LATE");
    }

    @Test
    void aSlowPhoneThatQueuedForTenMinutesIsLateWhateverItsClockError() throws Exception {
        Instant sentAt = clock.instant().minus(Duration.ofHours(1)); // phone clock is an hour slow
        Map<String, String> form = client.form("CLOCK_IN", sentAt.minus(Duration.ofMinutes(10)));
        form.put("deviceSentAt", sentAt.toString());
        JsonNode body = created(client.actor(), form);
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(3600);
        assertThat(reasons(body)).containsExactly("SYNCED_LATE");
    }

    @Test
    void theQueueDelayToleranceIsTwoMinutesInclusive() throws Exception {
        Map<String, String> atLimit = client.form("CLOCK_IN", clock.instant().minusSeconds(120));
        atLimit.put("deviceSentAt", clock.instant().toString());
        assertThat(created(client.actor(), atLimit).get("status").asText()).isEqualTo("VALID");

        Map<String, String> over = client.form("CLOCK_IN", clock.instant().minusSeconds(121));
        over.put("deviceSentAt", clock.instant().toString());
        assertThat(reasons(created(client.actor(), over))).containsExactly("SYNCED_LATE");
    }

    @Test
    void aPhoneThatIsAheadWhileSendingImmediatelyIsFlagged() throws Exception {
        Instant ahead = clock.instant().plus(Duration.ofMinutes(10));
        Map<String, String> form = client.form("CLOCK_IN", ahead);
        form.put("deviceSentAt", ahead.toString());
        JsonNode body = created(client.actor(), form);
        assertThat(reasons(body)).containsExactly("DEVICE_CLOCK_AHEAD");
        assertThat(body.get("skewSeconds").asInt()).isEqualTo(-600);
    }

    @Test
    void todayReportsTheGeofenceVerdictDerivedFromTheStoredReasons() throws Exception {
        CheckInClient.Actor outside = client.actor();
        Map<String, String> far = client.form("CLOCK_IN", clock.instant());
        far.put("lat", latAt(420));
        created(outside, far);
        JsonNode farIn = today(outside).get("clockIn");
        assertThat(farIn.get("insideGeofence").asBoolean()).isFalse();
        assertThat(farIn.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(farIn.get("distanceMeters").asInt()).isBetween(419, 421);

        CheckInClient.Actor blurry = client.actor();
        Map<String, String> lowAccuracy = client.form("CLOCK_IN", clock.instant());
        lowAccuracy.put("accuracy", "800");
        created(blurry, lowAccuracy);
        assertThat(today(blurry).get("clockIn").get("insideGeofence").asBoolean()).isFalse();

        CheckInClient.Actor noGps = client.actor();
        Map<String, String> none = client.form("CLOCK_IN", clock.instant());
        none.remove("lat");
        none.remove("lng");
        none.remove("accuracy");
        created(noGps, none);
        JsonNode summary = today(noGps).get("clockIn");
        assertThat(summary.get("insideGeofence").isNull()).isTrue();
        assertThat(summary.get("distanceMeters").isNull()).isTrue();
        assertThat(summary.get("hasPhoto").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- lateness

    @Test
    void theGraceBoundaryThroughTheApiIsFiveMinutesFiftyNineSecondsOnTimeAndSixMinutesLate() throws Exception {
        clock.advanceToNext(CR, LocalTime.of(8, 5, 59));
        int isoDay = clock.instant().atZone(CR).getDayOfWeek().getValue();
        CheckInClient.Actor onTime = client.actor();
        CheckInClient.Actor late = client.actor();
        for (CheckInClient.Actor a : List.of(onTime, late)) {
            data.schedule(a.companyId(), a.employeeId(), isoDay, LocalTime.of(8, 0), LocalTime.of(17, 0), 5);
        }
        JsonNode first = created(onTime, client.form("CLOCK_IN", clock.instant()));
        assertThat(first.get("lateMinutes").asInt()).isZero();

        clock.advance(Duration.ofSeconds(1)); // 08:06:00
        JsonNode second = created(late, client.form("CLOCK_IN", clock.instant()));
        assertThat(second.get("lateMinutes").asInt()).isEqualTo(6);
        assertThat(second.get("status").asText()).isEqualTo("VALID"); // lateness alone is not a review reason
    }

    // ---------------------------------------------------------------- other company zones

    private TestData.Tenant tenantInZone(String zone) {
        TestData.Tenant tenant = data.tenant();
        jdbc.update("update company set time_zone = ? where id = ?", zone, tenant.companyId());
        return tenant;
    }

    @Test
    void aCompanyOnTheOtherSideOfTheDateLineUsesItsOwnZoneForWorkDateLatenessAndTime() throws Exception {
        ZoneId kiritimati = ZoneId.of("Pacific/Kiritimati"); // UTC+14
        Instant arrival = clock.advanceToNext(kiritimati, LocalTime.of(8, 12));
        LocalDate localDay = arrival.atZone(kiritimati).toLocalDate();
        assertThat(arrival.atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(localDay.minusDays(1));

        TestData.Tenant tenant = tenantInZone("Pacific/Kiritimati");
        CheckInClient.Actor actor = client.actor(tenant, "EMPLOYEE", true);
        data.schedule(actor.companyId(), actor.employeeId(), localDay.getDayOfWeek().getValue(),
                LocalTime.of(8, 0), LocalTime.of(17, 0), 5);

        JsonNode time = client.json(client.get(actor, "/api/time").andExpect(status().isOk()));
        assertThat(time.get("timeZone").asText()).isEqualTo("Pacific/Kiritimati");
        assertThat(time.get("localDateTime").asText()).startsWith(localDay + "T08:12");
        assertThat(today(actor).get("workDate").asText()).isEqualTo(localDay.toString());

        TimeZone original = TimeZone.getDefault();
        JsonNode in;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            in = created(actor, client.form("CLOCK_IN", clock.instant()));
        } finally {
            TimeZone.setDefault(original);
        }
        assertThat(in.get("workDate").asText()).isEqualTo(localDay.toString());
        assertThat(in.get("lateMinutes").asInt()).isEqualTo(12);
        assertThat(jdbc.queryForObject("select work_date::text from time_entry where id = ?", String.class,
                in.get("id").asLong())).isEqualTo(localDay.toString());
    }

    // ---------------------------------------------------------------- request binding oddities

    @Test
    void unknownAndMassAssignmentFieldsAreIgnored() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", clock.instant());
        form.put("source", "TABLET");
        form.put("status", "REJECTED");
        form.put("workDate", "2000-01-01");
        form.put("lateMinutes", "99");
        form.put("companyId", "999999");
        form.put("employeeId", "1");
        form.put("id", "1");
        form.put("serverTime", "2000-01-01T00:00:00Z");
        JsonNode body = created(actor, form);

        Map<String, Object> row = jdbc.queryForMap("""
                select company_id, employee_id, source, status, work_date::text as work_date, late_minutes,
                       server_time from time_entry where id = ?
                """, body.get("id").asLong());
        assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(actor.companyId());
        assertThat(((Number) row.get("employee_id")).longValue()).isEqualTo(actor.employeeId());
        assertThat(row.get("source")).isEqualTo("MOBILE");
        assertThat(row.get("status")).isEqualTo("VALID");
        assertThat(row.get("work_date")).isEqualTo(clock.instant().atZone(CR).toLocalDate().toString());
        assertThat(row.get("late_minutes")).isNull();
        assertThat(Instant.parse(body.get("serverTime").asText())).isEqualTo(clock.instant());
    }

    @Test
    void repeatedFieldsAndAPhotoSentAsPlainTextNeverCauseAServerError() throws Exception {
        CheckInClient.Actor actor = client.actor();
        MockHttpServletResponse twice = client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).param("type", "CLOCK_OUT"))
                .andReturn().getResponse();
        assertThat(twice.getStatus()).isLessThan(500);

        MockHttpServletResponse asText = client.perform(client.request(actor.token(),
                client.form("CLOCK_IN", clock.instant()), null).param("photo", "I am not a file"))
                .andReturn().getResponse();
        assertThat(asText.getStatus()).isEqualTo(400);
        assertThat(asText.getContentAsString()).doesNotContain("I am not a file");
    }

    // ---------------------------------------------------------------- authentication handling

    private void assertUnauthorizedEverywhere(String authorization, String label, long entryId) throws Exception {
        List<ResultActions> calls = new ArrayList<>();
        for (String path : new String[] {"/api/time", "/api/time-entries/today",
            "/api/time-entries/" + entryId + "/photo", "/api/admin/time-entries/" + entryId + "/photo"}) {
            var builder = get(path);
            if (authorization != null) {
                builder.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            calls.add(client.perform(builder));
        }
        var post = client.request(null, Map.of("type", "CLOCK_IN"), TestImages.jpeg());
        if (authorization != null) {
            post.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        calls.add(client.perform(post));
        for (ResultActions call : calls) {
            MockHttpServletResponse response = call.andReturn().getResponse();
            assertThat(response.getStatus()).as(label + " " + response.getContentAsString()).isEqualTo(401);
            assertThat(response.getContentAsString()).as(label).doesNotContain("eyJ");
        }
    }

    @Test
    void garbageStaleAndForgedTokensAreUnauthorizedOnEveryNewEndpoint() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = created(actor, client.form("CLOCK_IN", clock.instant())).get("id").asLong();
        long cid = actor.companyId();
        long eid = actor.employeeId();

        assertUnauthorizedEverywhere(null, "no header", id);
        assertUnauthorizedEverywhere("Bearer not.a.jwt", "garbage", id);
        assertUnauthorizedEverywhere("Bearer ", "empty bearer", id);
        assertUnauthorizedEverywhere("Basic dXNlcjpwYXNz", "basic scheme", id);
        assertUnauthorizedEverywhere("Bearer " + tokens.expired(eid, cid, "EMPLOYEE", 0), "expired", id);
        assertUnauthorizedEverywhere("Bearer " + tokens.otherKey(eid, cid, "EMPLOYEE", 0), "other key", id);
        assertUnauthorizedEverywhere("Bearer " + tokens.algNone(eid, cid, "EMPLOYEE", 0), "alg none", id);
        assertUnauthorizedEverywhere("Bearer " + tokens.withTamperedPayload(actor.token(), cid + 1000),
                "tampered company", id);
    }

    @Test
    void aTokenIsDeadAsSoonAsTheEmployeeIsDeactivatedOrTheirTokensAreRevoked() throws Exception {
        CheckInClient.Actor revoked = client.actor();
        long revokedId = created(revoked, client.form("CLOCK_IN", clock.instant())).get("id").asLong();
        jdbc.update("update employee set token_version = token_version + 1 where id = ?", revoked.employeeId());
        assertUnauthorizedEverywhere("Bearer " + revoked.token(), "revoked version", revokedId);

        CheckInClient.Actor inactive = client.actor();
        long inactiveId = created(inactive, client.form("CLOCK_IN", clock.instant())).get("id").asLong();
        jdbc.update("update employee set active = false where id = ?", inactive.employeeId());
        assertUnauthorizedEverywhere("Bearer " + inactive.token(), "inactive employee", inactiveId);

        CheckInClient.Actor closed = client.actor();
        long closedId = created(closed, client.form("CLOCK_IN", clock.instant())).get("id").asLong();
        jdbc.update("update company set active = false where id = ?", closed.companyId());
        assertUnauthorizedEverywhere("Bearer " + closed.token(), "inactive company", closedId);
        assertThat(client.entryCount(closed.employeeId())).isEqualTo(1);
    }

    @Test
    void aTokenFromACompanyWhoseEmployeeIdBelongsToAnotherCompanyIsRejected() throws Exception {
        CheckInClient.Actor a = client.actor();
        CheckInClient.Actor b = client.actor();
        // Employee A's id with company B's id in the claims: the DB re-check must not accept the pair.
        String forged = tokens.valid(a.employeeId(), b.companyId(), "EMPLOYEE", 0);
        client.perform(get("/api/time-entries/today").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        client.perform(get("/api/time").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anEmployeeTokenIsNotAnAdminTokenEvenWithAnAdminRoleClaim() throws Exception {
        CheckInClient.Actor employee = client.actor();
        long id = created(employee, client.form("CLOCK_IN", clock.instant())).get("id").asLong();
        String claimsAdmin = tokens.valid(employee.employeeId(), employee.companyId(), "ADMIN", 0);
        // The role is read from the database, not from the token.
        client.perform(get("/api/admin/time-entries/" + id + "/photo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + claimsAdmin)).andExpect(status().isForbidden());
    }
}
