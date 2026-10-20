package cr.asistencia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
class SecurityAndActuatorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Test
    void actuatorHealthIsPublicAndHidesDetails() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc", "postgres");
    }

    @Test
    void sensitiveActuatorEndpointsAreDenied() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/heapdump",
                "/actuator/metrics", "/actuator/info", "/actuator/flyway", "/actuator"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unknownApiPathsAreDenied() throws Exception {
        mockMvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health/extra")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonGetOnHealthIsNotPublic() throws Exception {
        mockMvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void noWwwAuthenticateChallengeOrSessionCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/anything")).andReturn();
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
        assertThat(result.getResponse().getCookies()).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void clockIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(Instant.now(clock)).isBetween(Instant.now().minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void timeConfigBuildsUtcClockWithoutSpring() {
        Clock c = new TimeConfig().clock();
        assertThat(c.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
