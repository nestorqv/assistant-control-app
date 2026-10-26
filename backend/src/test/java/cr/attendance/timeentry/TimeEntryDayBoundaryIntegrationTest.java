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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEntryDayBoundaryIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Test
    void aShiftStartedBeforeLocalMidnightKeepsItsWorkDateAfterMidnight() throws Exception {
        Instant start = clock.advanceToNext(CR, LocalTime.of(23, 59));
        LocalDate day = start.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();

        JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(in.get("workDate").asText()).isEqualTo(day.toString());

        clock.advance(Duration.ofMinutes(2)); // 00:01 local, the next local day
        assertThat(clock.instant().atZone(CR).toLocalDate()).isEqualTo(day.plusDays(1));
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        assertThat(today.get("workDate").asText()).isEqualTo(day.toString());

        JsonNode out = client.json(client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(out.get("workDate").asText()).isEqualTo(day.toString());
    }

    @Test
    void theCompanyZoneDecidesTheWorkDateNotUtc() throws Exception {
        // 20:00 in Costa Rica is already 02:00 of the next day in UTC.
        Instant start = clock.advanceToNext(CR, LocalTime.of(20, 0));
        LocalDate localDay = start.atZone(CR).toLocalDate();
        LocalDate utcDay = start.atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(utcDay).isNotEqualTo(localDay);

        CheckInClient.Actor actor = client.actor();
        JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated()));
        assertThat(in.get("workDate").asText()).isEqualTo(localDay.toString());
        JsonNode today = client.json(client.get(actor, "/api/time-entries/today"));
        assertThat(today.get("workDate").asText()).isEqualTo(localDay.toString());
    }

    @Test
    void theJvmDefaultZoneDoesNotChangeTheWorkDate() throws Exception {
        Instant start = clock.advanceToNext(CR, LocalTime.of(23, 30)); // 14:30 the next day in Tokyo
        LocalDate localDay = start.atZone(CR).toLocalDate();
        CheckInClient.Actor actor = client.actor();

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            JsonNode in = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()),
                    TestImages.jpeg()).andExpect(status().isCreated()));
            assertThat(in.get("workDate").asText()).isEqualTo(localDay.toString());
            String stored = client.json(client.get(actor, "/api/time-entries/today")).get("workDate").asText();
            assertThat(stored).isEqualTo(localDay.toString());
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
