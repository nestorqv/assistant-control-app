package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class LoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private ResultActions login(String company, String employee, String pin) throws Exception {
        return login(company, employee, pin, null);
    }

    private ResultActions login(String company, String employee, String pin, String language) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "companyCode", company, "employeeCode", employee, "pin", pin)));
        if (language != null) {
            request.header("Accept-Language", language);
        }
        return mockMvc.perform(request);
    }

    @Test
    void employeeLoginReturnsTokenWithEightHourTtl() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");

        String body = login(t.companyCode(), u.code(), u.pin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.employee.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.employee.consentPending").value(true))
                .andExpect(jsonPath("$.company.timeZone").value("America/Costa_Rica"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("pinHash").doesNotContain("$2a$");
        Instant expiresAt = Instant.parse(mapper.readTree(body).get("expiresAt").asText());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofHours(8).minusMinutes(1),
                Duration.ofHours(8).plusMinutes(1));
    }

    @Test
    void adminLoginHasSixHourTtl() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser admin = data.user(t, "ADMIN");

        String body = login(t.companyCode(), admin.code(), admin.pin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();

        Instant expiresAt = Instant.parse(mapper.readTree(body).get("expiresAt").asText());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofHours(6).minusMinutes(1),
                Duration.ofHours(6).plusMinutes(1));
    }

    @Test
    void tokenCarriesTheDocumentedClaims() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        String token = data.login(t, u);

        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        JsonNode claims = mapper.readTree(payload);
        assertThat(claims.get("iss").asText()).isEqualTo("attendance-backend");
        assertThat(claims.get("sub").asText()).isEqualTo(String.valueOf(u.id()));
        assertThat(claims.get("companyId").asLong()).isEqualTo(t.companyId());
        assertThat(claims.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(claims.get("ver").asInt()).isZero();
        assertThat(claims.has("iat") && claims.has("exp") && claims.has("jti")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void everyFailureHasTheSameStatusAndBody(String language) throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        TestData.TestUser inactiveUser = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set active = false where id = ?", inactiveUser.id());

        // Identical within one language: the body never reveals which check failed.
        String wrongPin = failureBody(login(t.companyCode(), u.code(), "11112222", language));
        String unknownCompany = failureBody(login("DOESNOTEXIST", u.code(), u.pin(), language));
        String unknownEmployee = failureBody(login(t.companyCode(), "DOESNOTEXIST", u.pin(), language));
        String inactive = failureBody(login(t.companyCode(), inactiveUser.code(), inactiveUser.pin(), language));
        String badFormat = failureBody(login(t.companyCode(), u.code(), "abc", language));

        assertThat(wrongPin).contains("INVALID_CREDENTIALS");
        assertThat(unknownCompany).isEqualTo(wrongPin);
        assertThat(unknownEmployee).isEqualTo(wrongPin);
        assertThat(inactive).isEqualTo(wrongPin);
        assertThat(badFormat).isEqualTo(wrongPin);
    }

    @Test
    void inactiveCompanyCannotLogIn() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        jdbc.update("update company set active = false where id = ?", t.companyId());
        String body = failureBody(login(t.companyCode(), u.code(), u.pin()));
        assertThat(body).contains("INVALID_CREDENTIALS");
    }

    private String failureBody(ResultActions actions) throws Exception {
        return actions.andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void lowerCaseAndPaddedCodesStillLogIn() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        login(" " + t.companyCode().toLowerCase() + " ", u.code().toLowerCase(), u.pin())
                .andExpect(status().isOk());
    }

    @Test
    void missingFieldsGiveBadRequestWithoutEchoingInput() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyCode\":\"\",\"pin\":\"secret-that-must-not-leak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-that-must-not-leak"));
    }

    @Test
    void oldSpanishFieldNamesAreRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empresaCodigo\":\"DEMO\",\"empleadoCodigo\":\"1001\",\"pin\":\"48207315\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void oversizedPinIsBadRequest() throws Exception {
        login("DEMO", "1001", "9".repeat(10_000)).andExpect(status().isBadRequest());
    }

    @Test
    void staleAuthorizationHeaderOnLoginIsIgnored() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer this.is.not-a-valid-token")
                        .content(mapper.writeValueAsString(Map.of("companyCode", t.companyCode(),
                                "employeeCode", u.code(), "pin", u.pin()))))
                .andExpect(status().isOk());
    }
}
