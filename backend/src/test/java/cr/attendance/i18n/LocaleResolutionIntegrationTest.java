package cr.attendance.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Accept-Language drives the language of title/detail; the machine code never changes. */
@IntegrationTest
class LocaleResolutionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private MessageSource messages;

    @Autowired
    private ObjectMapper mapper;

    private String text(ErrorCode code, String part, String lang) {
        return messages.getMessage(code.messageKey(part), null, Locale.of(lang));
    }

    private MockHttpServletResponse wrongPinLogin(String acceptLanguage) throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        return send(withLanguage(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "companyCode", t.companyCode(), "employeeCode", u.code(), "pin", "11112222"))),
                acceptLanguage));
    }

    private MockHttpServletRequestBuilder withLanguage(MockHttpServletRequestBuilder request, String header) {
        return header == null ? request : request.header("Accept-Language", header);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    // The header value uses ';' for q-values, so the CSV delimiter is '|'.
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "en|en", "en-US,en;q=0.9|en", "es|es", "es-CR|es", "NULL|es", "fr|es", "fr, en;q=0.5|en", "*|es",
            "@@@|es"})
    void wrongPinLoginIsLocalized(String header, String lang) throws Exception {
        MockHttpServletResponse r = wrongPinLogin(header);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.INVALID_CREDENTIALS, "detail", lang));
        assertThat(body.get("title").asText()).isEqualTo(text(ErrorCode.INVALID_CREDENTIALS, "title", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
    }

    @Test
    void bothLanguagesShareCodeStatusAndInstanceButNotText() throws Exception {
        JsonNode es = mapper.readTree(wrongPinLogin("es").getContentAsString());
        JsonNode en = mapper.readTree(wrongPinLogin("en").getContentAsString());
        assertThat(en.get("code")).isEqualTo(es.get("code"));
        assertThat(en.get("status")).isEqualTo(es.get("status"));
        assertThat(en.get("instance")).isEqualTo(es.get("instance"));
        assertThat(en.get("detail")).isNotEqualTo(es.get("detail"));
        assertThat(en.get("title")).isNotEqualTo(es.get("title"));
    }

    @ParameterizedTest
    @CsvSource({"es", "en"})
    void notFoundIsLocalized(String lang) throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long otherEmployee = data.user(b, "EMPLOYEE").id();
        String token = data.login(a, data.user(a, "ADMIN"));
        MockHttpServletResponse r = send(get("/api/admin/employees/" + otherEmployee)
                .header("Authorization", "Bearer " + token).header("Accept-Language", lang));
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(404);
        assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.NOT_FOUND, "detail", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
    }

    @ParameterizedTest
    @CsvSource({"es", "en"})
    void malformedJsonIsLocalized(String lang) throws Exception {
        MockHttpServletResponse r = send(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyCode\": ").header("Accept-Language", lang));
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.INVALID_REQUEST, "detail", lang));
        assertThat(body.get("title").asText()).isEqualTo(text(ErrorCode.INVALID_REQUEST, "title", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
    }
}
