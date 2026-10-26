package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestImages;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "app.time-entry.rate-limit.max-attempts=3")
class TimeEntryRateLimitIntegrationTest {

    @Autowired
    private CheckInClient client;

    @Test
    void theFourthPostIsRefusedAndAnotherEmployeeIsUnaffected() throws Exception {
        CheckInClient.Actor actor = client.actor();
        CheckInClient.Actor other = client.actor();
        for (int i = 0; i < 3; i++) {
            // The result does not matter (201 then 409s): every attempt counts.
            client.post(actor, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg());
        }

        MockHttpServletResponse refused = client.post(actor, client.form("CLOCK_IN", Instant.now()),
                TestImages.jpeg()).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(refused.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("600");
        assertThat(client.json(client.post(actor, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg()))
                .get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");

        client.post(other, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg())
                .andExpect(status().isCreated());
    }

    @Test
    void malformedPostsCountTowardTheLimitAndAreRefusedBeforeValidation() throws Exception {
        CheckInClient.Actor actor = client.actor();
        for (int i = 0; i < 3; i++) {
            Map<String, String> bad = client.form("CLOCK_IN", Instant.now());
            bad.remove("type");
            client.post(actor, bad, TestImages.jpeg()).andExpect(status().isBadRequest());
        }
        Map<String, String> bad = client.form("CLOCK_IN", Instant.now());
        bad.remove("type");
        JsonNode body = client.json(client.post(actor, bad, TestImages.jpeg()).andExpect(status().isTooManyRequests()));
        assertThat(body.get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(client.entryCount(actor.employeeId())).isZero();
    }

    @Test
    void replaysCountAndTheLimitBeatsAReplay() throws Exception {
        CheckInClient.Actor actor = client.actor();
        Map<String, String> form = client.form("CLOCK_IN", Instant.now());
        client.post(actor, form, TestImages.jpeg()).andExpect(status().isCreated());
        client.post(actor, form, TestImages.jpeg()).andExpect(status().isOk());
        client.post(actor, form, null).andExpect(status().isOk());

        // The 4th attempt is a perfectly good replay, but the limiter runs first.
        JsonNode body = client.json(client.post(actor, form, null).andExpect(status().isTooManyRequests()));
        assertThat(body.get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(client.entryCount(actor.employeeId())).isEqualTo(1);
    }

    /** The limiter is a security-chain filter, so it runs before content-type matching: 429 beats 415. */
    @Test
    void aJsonBodyCountsTowardTheLimit() throws Exception {
        CheckInClient.Actor actor = client.actor();
        for (int i = 0; i < 3; i++) {
            client.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/time-entries").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{}").header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token()))
                    .andExpect(status().isUnsupportedMediaType());
        }
        client.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/time-entries").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}").header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aRefusedPostDoesNotWriteAnythingNew() throws Exception {
        CheckInClient.Actor actor = client.actor();
        // Company ids restart in every embedded database, so compare with what is on disk before the post.
        int filesBefore = client.filesOf(actor.companyId()).size();
        for (int i = 0; i < 3; i++) {
            client.post(actor, client.form("CLOCK_IN", Instant.now()), null).andExpect(status().isBadRequest());
        }
        client.post(actor, client.form("CLOCK_IN", Instant.now()), TestImages.jpeg())
                .andExpect(status().isTooManyRequests());
        assertThat(client.filesOf(actor.companyId())).hasSize(filesBefore);
        assertThat(client.entryCount(actor.employeeId())).isZero();
    }

    @Test
    void readsAreNotLimited() throws Exception {
        CheckInClient.Actor actor = client.actor();
        for (int i = 0; i < 6; i++) {
            client.get(actor, "/api/time-entries/today").andExpect(status().isOk());
        }
    }
}
