package cr.attendance.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.IntegrationTest;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The 429 body is localized like every other error and keeps Retry-After and the stable code. */
@IntegrationTest
@TestPropertySource(properties = "app.security.login-rate-limit.max-attempts=2")
class RateLimitLocalizationIntegrationTest {

    private static final String BODY = "{\"companyCode\":\"DOESNOTEXIST\",\"employeeCode\":\"X\",\"pin\":\"48207315\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageSource messages;

    @Autowired
    private ObjectMapper mapper;

    private MockHttpServletResponse login(String lang) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("Accept-Language", lang)).andReturn().getResponse();
    }

    @Test
    void tooManyAttemptsIsLocalizedInBothLanguagesWithRetryAfterAndSameCode() throws Exception {
        assertThat(login("es").getStatus()).isEqualTo(401);
        assertThat(login("es").getStatus()).isEqualTo(401);

        MockHttpServletResponse en = login("en");
        MockHttpServletResponse es = login("es");
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {en, es}) {
            assertThat(r.getStatus()).isEqualTo(429);
            assertThat(r.getHeader("Retry-After")).isEqualTo("300");
            assertThat(mapper.readTree(r.getContentAsString()).get("code").asText()).isEqualTo("TOO_MANY_ATTEMPTS");
        }
        JsonNode enBody = mapper.readTree(en.getContentAsString());
        JsonNode esBody = mapper.readTree(es.getContentAsString());
        assertThat(en.getHeader("Content-Language")).isEqualTo("en");
        assertThat(es.getHeader("Content-Language")).isEqualTo("es");
        assertThat(enBody.get("detail").asText())
                .isEqualTo(messages.getMessage(ErrorCode.TOO_MANY_ATTEMPTS.messageKey("detail"), null,
                        Locale.of("en")));
        assertThat(esBody.get("detail").asText())
                .isEqualTo(messages.getMessage(ErrorCode.TOO_MANY_ATTEMPTS.messageKey("detail"), null,
                        Locale.of("es")));
        assertThat(enBody.get("detail")).isNotEqualTo(esBody.get("detail"));
        assertThat(enBody.get("title")).isNotEqualTo(esBody.get("title"));
    }
}
