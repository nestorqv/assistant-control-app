package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(MutableClockConfig.class)
class TimeEndpointIntegrationTest {

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Test
    void withoutATokenItIsUnauthorized() throws Exception {
        client.get(null, "/api/time").andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheServerClockAndTheCompanyZone() throws Exception {
        CheckInClient.Actor actor = client.actor();
        JsonNode body = client.json(client.get(actor, "/api/time").andExpect(status().isOk()));

        Instant serverTime = Instant.parse(body.get("serverTime").asText());
        assertThat(serverTime).isEqualTo(clock.instant());
        assertThat(body.get("epochMillis").asLong()).isEqualTo(clock.instant().toEpochMilli());
        assertThat(body.get("timeZone").asText()).isEqualTo("America/Costa_Rica");
        assertThat(body.get("localDateTime").asText()).isNotBlank();

        clock.advance(java.time.Duration.ofMinutes(5));
        JsonNode later = client.json(client.get(actor, "/api/time"));
        assertThat(Instant.parse(later.get("serverTime").asText())).isAfter(serverTime);
    }
}
