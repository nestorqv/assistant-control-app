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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class OvernightShiftIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private JsonNode post(CheckInClient.Actor actor, String type, int expectedStatus) throws Exception {
        return client.json(client.post(actor, client.form(type, clock.instant()), TestImages.jpeg())
                .andExpect(status().is(expectedStatus)));
    }

    private static List<String> reasons(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("reviewReasons").forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void aMondayNightShiftClosesOnTuesdayAndBelongsToMonday() throws Exception {
        Instant monday = clock.advanceToNext(CR, DayOfWeek.MONDAY, LocalTime.of(22, 3));
        LocalDate mondayDate = monday.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();
        data.schedule(actor.companyId(), actor.employeeId(), 1, LocalTime.of(22, 0), LocalTime.of(6, 0), 5);

        JsonNode in = post(actor, "CLOCK_IN", 201);
        assertThat(in.get("lateMinutes").asInt()).isZero();
        assertThat(in.get("workDate").asText()).isEqualTo(mondayDate.toString());

        clock.advance(Duration.ofHours(7).plusMinutes(59)); // Tuesday 06:02
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("workDate").asText()).isEqualTo(mondayDate.toString());
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        assertThat(today.get("workedMinutes").asLong()).isEqualTo(479);
        assertThat(today.get("schedule").get("overnight").asBoolean()).isTrue();

        JsonNode out = post(actor, "CLOCK_OUT", 201);
        assertThat(out.get("workDate").asText()).isEqualTo(mondayDate.toString());
        assertThat(jdbc.queryForObject("""
                select extract(epoch from (o.server_time - i.server_time)) / 60
                  from time_entry o join time_entry i on i.employee_id = o.employee_id and i.type = 'CLOCK_IN'
                 where o.id = ?
                """, Long.class, out.get("id").asLong())).isEqualTo(479);

        // Deferred to the PWA (Stage 3): the day after an overnight shift, the API offers CLOCK_IN again.
        today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(today.get("workDate").asText()).isEqualTo(mondayDate.plusDays(1).toString());

        clock.advance(Duration.ofHours(15).plusMinutes(56)); // Tuesday 21:58
        JsonNode tuesday = post(actor, "CLOCK_IN", 201);
        assertThat(tuesday.get("workDate").asText()).isEqualTo(mondayDate.plusDays(1).toString());
    }

    @Test
    void aForgottenClockOutDoesNotBlockTheNextDay() throws Exception {
        Instant start = clock.advanceToNext(CR, LocalTime.of(8, 0));
        LocalDate firstDay = start.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();
        post(actor, "CLOCK_IN", 201);

        clock.advance(Duration.ofHours(16).plusMinutes(1)); // 00:01 on the next local day
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(today.get("workDate").asText()).isEqualTo(firstDay.plusDays(1).toString());
        assertThat(today.get("inProgress").asBoolean()).isFalse();
        assertThat(today.get("missingClockOut").asBoolean()).isTrue();
        assertThat(today.get("forgottenWorkDate").asText()).isEqualTo(firstDay.toString());

        JsonNode second = post(actor, "CLOCK_IN", 201);
        assertThat(second.get("workDate").asText()).isEqualTo(firstDay.plusDays(1).toString());
    }

    @Test
    void aClockOutAfterTheWindowIsAcceptedAndSentToReview() throws Exception {
        Instant start = clock.advanceToNext(CR, LocalTime.of(8, 0));
        LocalDate day = start.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();
        post(actor, "CLOCK_IN", 201);

        clock.advance(Duration.ofHours(17)); // 01:00 the next local day, past the 16 h window
        JsonNode out = post(actor, "CLOCK_OUT", 201);
        assertThat(out.get("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(reasons(out)).containsExactly("SHIFT_TOO_LONG");
        assertThat(out.get("workDate").asText()).isEqualTo(day.toString());
    }

    @Test
    void anExpiredShiftOfTheSameLocalDayStaysOpenForTheClockOut() throws Exception {
        Instant start = clock.advanceToNext(CR, LocalTime.of(0, 30));
        LocalDate day = start.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();
        post(actor, "CLOCK_IN", 201);

        clock.advance(Duration.ofHours(16).plusMinutes(31)); // 17:01 the same local day
        assertThat(clock.instant().atZone(CR).toLocalDate()).isEqualTo(day);
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        assertThat(today.get("missingClockOut").asBoolean()).isTrue();

        client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isConflict()));
        JsonNode out = post(actor, "CLOCK_OUT", 201);
        assertThat(reasons(out)).containsExactly("SHIFT_TOO_LONG");
        assertThat(out.get("workDate").asText()).isEqualTo(day.toString());
    }
}
