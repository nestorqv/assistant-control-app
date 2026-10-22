package cr.attendance.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Wider Accept-Language matrix and non-login error kinds, end to end through the real context. */
@IntegrationTest
class ErrorLocalizationEdgeCasesIntegrationTest {

    private static final String MARKER = "ZZ-MARKER-9137";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessageSource messages;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TestData data;

    /** Authenticated PUT on a GET-only path reaches MVC and yields 405 (anonymous would be 401 first). */
    private MockHttpServletRequestBuilder putHealth() throws Exception {
        TestData.Tenant t = data.tenant();
        return put("/api/health").header("Authorization", "Bearer " + data.login(t, data.user(t, "EMPLOYEE")));
    }

    private String text(ErrorCode code, String part, String lang) {
        return messages.getMessage(code.messageKey(part), null, Locale.of(lang));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String acceptLanguage)
            throws Exception {
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder badJson() {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyCode\":\"" + MARKER + "\",");
    }

    static Stream<Arguments> headers() {
        return Stream.of(
                Arguments.of("EN", "en"),
                Arguments.of("En-gb", "en"),
                Arguments.of("en_US", "es"),
                Arguments.of("en;q=abc", "es"),
                Arguments.of("", "es"),
                Arguments.of(" ", "es"),
                Arguments.of(",", "es"),
                Arguments.of("zh-CN,zh;q=0.9", "es"),
                Arguments.of("de, fr;q=0.9, en;q=0.1", "en"),
                Arguments.of("fr;q=1, en;q=0.5", "en"),
                Arguments.of("en;q=0.2, es;q=0.8", "es"),
                Arguments.of("es-419", "es"),
                Arguments.of("en-US,en;q=0.9,es;q=0.8", "en"),
                Arguments.of("*;q=0.5", "es"),
                Arguments.of("ééé", "es"),
                Arguments.of("en,".repeat(300), "en"));
    }

    @ParameterizedTest
    @MethodSource("headers")
    void acceptLanguageMatrixSelectsTheBundleAndContentLanguage(String header, String lang) throws Exception {
        MockHttpServletResponse r = perform(badJson(), header);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.INVALID_REQUEST, "detail", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void methodNotAllowedKeepsAllowHeaderAndIsLocalized(String lang) throws Exception {
        MockHttpServletResponse r = perform(putHealth(), lang);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(405);
        assertThat(r.getHeader("Allow")).contains("GET");
        assertThat(body.get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(body.get("title").asText()).isEqualTo(text(ErrorCode.METHOD_NOT_ALLOWED, "title", lang));
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.METHOD_NOT_ALLOWED, "detail", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void unsupportedMediaTypeIsLocalizedAndDoesNotEchoTheBody(String lang) throws Exception {
        MockHttpServletResponse r = perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN)
                .content(MARKER), lang);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(415);
        assertThat(body.get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "detail", lang));
        assertThat(r.getHeader("Content-Language")).isEqualTo(lang);
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void validationFailureIsLocalizedAndDoesNotEchoInput(String lang) throws Exception {
        MockHttpServletResponse r = perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyCode\":\"\",\"employeeCode\":\"" + MARKER + "\",\"pin\":\"\"}"), lang);
        JsonNode body = mapper.readTree(r.getContentAsString());
        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("detail").asText()).isEqualTo(text(ErrorCode.INVALID_REQUEST, "detail", lang));
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void unknownPathHasNoLocalizedBodyLeakingInput(String lang) throws Exception {
        MockHttpServletResponse r = perform(get("/api/" + MARKER), lang);
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
        assertThat(r.getStatus()).isIn(401, 404);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void noErrorBodyHasUnrenderedPlaceholdersOrMissingKeyMarkers(String lang) throws Exception {
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {
                perform(badJson(), lang),
                perform(putHealth(), lang),
                perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"), lang)}) {
            String json = r.getContentAsString();
            assertThat(json).doesNotContain("{0}", "{1}", "??", "error.");
            JsonNode body = mapper.readTree(json);
            assertThat(body.get("title").asText()).isNotBlank();
            assertThat(body.get("detail").asText()).isNotBlank();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"es", "en"})
    void codeAndStatusDoNotDependOnTheLanguage(String lang) throws Exception {
        JsonNode body = mapper.readTree(perform(badJson(), lang).getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("instance").asText()).isEqualTo("urn:attendance:error");
    }
}
