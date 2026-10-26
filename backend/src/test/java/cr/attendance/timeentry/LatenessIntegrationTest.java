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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class LatenessIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lateMinutesCountFromTheScheduledStartAndAreStored() throws Exception {
        Instant arrival = clock.advanceToNext(CR, DayOfWeek.WEDNESDAY, LocalTime.of(8, 12));
        CheckInClient.Actor actor = client.actor();
        data.schedule(actor.companyId(), actor.employeeId(), arrival.atZone(CR).getDayOfWeek().getValue(),
                LocalTime.of(8, 0), LocalTime.of(17, 0), 5);

        JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));

        assertThat(in.get("lateMinutes").asInt()).isEqualTo(12);
        long id = in.get("id").asLong();
        assertThat(jdbc.queryForObject("select late_minutes from time_entry where id = ?", Integer.class, id))
                .isEqualTo(12);

        // Editing the schedule afterwards must not rewrite history.
        jdbc.update("update work_schedule set start_time = '10:00', grace_minutes = 60 where employee_id = ?",
                actor.employeeId());
        assertThat(jdbc.queryForObject("select late_minutes from time_entry where id = ?", Integer.class, id))
                .isEqualTo(12);
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("clockIn").get("lateMinutes").asInt()).isEqualTo(12);
    }

    @Test
    void arrivingWithinTheGraceIsNotLate() throws Exception {
        Instant arrival = clock.advanceToNext(CR, DayOfWeek.THURSDAY, LocalTime.of(8, 5));
        CheckInClient.Actor actor = client.actor();
        data.schedule(actor.companyId(), actor.employeeId(), arrival.atZone(CR).getDayOfWeek().getValue(),
                LocalTime.of(8, 0), LocalTime.of(17, 0), 5);
        JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(in.get("lateMinutes").asInt()).isZero();
        assertThat(in.get("status").asText()).isEqualTo("VALID");
    }

    @Test
    void noScheduleThatDayGivesNullAndStillValid() throws Exception {
        clock.advanceToNext(CR, DayOfWeek.SATURDAY, LocalTime.of(8, 12));
        CheckInClient.Actor actor = client.actor();
        data.schedule(actor.companyId(), actor.employeeId(), 1, LocalTime.of(8, 0), LocalTime.of(17, 0), 5);

        JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(in.get("lateMinutes").isNull()).isTrue();
        assertThat(in.get("status").asText()).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("select late_minutes from time_entry where id = ?", Integer.class,
                in.get("id").asLong())).isNull();
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("schedule").isNull()).isTrue();
    }
}
