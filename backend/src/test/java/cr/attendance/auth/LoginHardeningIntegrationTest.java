package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Login input validation, error-body shape and cross-tenant behavior of the login endpoint. */
@IntegrationTest
class LoginHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private MvcResult postLogin(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private MvcResult login(String company, String employee, String pin) throws Exception {
        return postLogin(mapper.writeValueAsString(Map.of(
                "companyCode", company, "employeeCode", employee, "pin", pin)));
    }

    private static String bodyText(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void blankAndOverlongFieldsAreBadRequest() throws Exception {
        String ok20 = "A".repeat(20);
        String ko21 = "A".repeat(21);
        assertThat(login("   ", "1001", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "   ", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "1001", "        ").getResponse().getStatus()).isEqualTo(400);
        assertThat(login(ko21, "1001", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", ko21, "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "1001", "1".repeat(13)).getResponse().getStatus()).isEqualTo(400);
        // Exactly at the limits: not a validation error, just a normal (failed) login.
        assertThat(login(ok20, ok20, "1".repeat(12)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void nullOrMissingFieldsAreBadRequest() throws Exception {
        assertThat(postLogin("{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(postLogin("{\"companyCode\":null,\"employeeCode\":null,\"pin\":null}").getResponse()
                .getStatus()).isEqualTo(400);
    }

    @Test
    void validationErrorBodyIsProblemDetailWithCodeAndNoInputEcho() throws Exception {
        MvcResult r = login("DEMO", "1001", "supersecret-long".repeat(2));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(bodyText(r)).contains("\"code\":\"INVALID_REQUEST\"").doesNotContain("supersecret");
    }

    @Test
    void malformedJsonIsBadRequestProblemDetailWithCode() throws Exception {
        MvcResult r = postLogin("{\"companyCode\": ");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(bodyText(r)).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    void unsupportedMediaTypeIsProblemDetailWithCodeAndNoInputEcho() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN)
                .content("companyCode=DEMO")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(415);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(bodyText(r)).contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"")
                .doesNotContain("text/plain").doesNotContain("companyCode");
    }

    @Test
    void methodNotAllowedIsProblemDetailWithCodeAndKeepsTheAllowHeader() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        // /api/health only allows GET; an authenticated PUT reaches MVC and gets 405.
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/health").header("Authorization", "Bearer " + data.login(t, u))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(405);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(r.getResponse().getHeader("Allow")).contains("GET");
        assertThat(bodyText(r)).contains("\"code\":\"METHOD_NOT_ALLOWED\"").doesNotContain("PUT");
    }

    @Test
    void malformedJsonDoesNotLeakInternals() throws Exception {
        String body = bodyText(postLogin("{\"pin\": \"secret-that-must-not-leak\" oops"));
        assertThat(body).doesNotContain("secret-that-must-not-leak").doesNotContain("com.fasterxml")
                .doesNotContain("at cr.attendance").doesNotContain("Exception");
    }

    @Test
    void wrongMethodAndMediaTypeDoNotLeakInternals() throws Exception {
        MvcResult nonJson = mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN)
                .content("companyCode=DEMO")).andReturn();
        assertThat(nonJson.getResponse().getStatus()).isEqualTo(415);
        assertThat(bodyText(nonJson)).doesNotContain("Exception").doesNotContain("at cr.attendance");

        MvcResult getLogin = mockMvc.perform(get("/api/auth/login")).andReturn();
        assertThat(getLogin.getResponse().getStatus()).isIn(401, 405);
    }

    @Test
    void failureBodiesNeverContainStackTracesOrSubmittedCodes() throws Exception {
        MvcResult r = login("DOESNOTEXIST-ZZ", "EMP-ZZ-9", "48207315");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(bodyText(r)).doesNotContain("DOESNOTEXIST-ZZ").doesNotContain("EMP-ZZ-9").doesNotContain("48207315")
                .doesNotContain("Exception").doesNotContain("at cr.attendance").doesNotContain("trace");
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
    }

    @Test
    void employeeCodeOfAnotherCompanyDoesNotAuthenticate() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser userB = data.user(b, "EMPLOYEE");
        MvcResult crossed = login(a.companyCode(), userB.code(), userB.pin());
        assertThat(crossed.getResponse().getStatus()).isEqualTo(401);
        MvcResult unknown = login(a.companyCode(), "DOES-NOT-EXIST", userB.pin());
        assertThat(bodyText(crossed)).isEqualTo(bodyText(unknown));
        // And the wrong-tenant attempt did not touch B's employee.
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                userB.id())).isZero();
    }

    @Test
    void sameEmployeeCodeInTwoCompaniesResolvesToTheRightTenant() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String hash = data.pinHash();
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            jdbc.update("""
                    insert into employee (company_id, branch_id, code, name, pin_hash, role)
                    values (?, ?, 'SHARED', 'X', ?, 'EMPLOYEE')
                    """, t.companyId(), t.branchId(), hash);
        }
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            MvcResult r = login(t.companyCode(), "SHARED", TestData.PIN);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            String payload = new String(java.util.Base64.getUrlDecoder()
                    .decode(mapper.readTree(bodyText(r)).get("token").asText().split("\\.")[1]));
            assertThat(mapper.readTree(payload).get("companyId").asLong()).isEqualTo(t.companyId());
        }
    }

    @Test
    void failuresInOneCompanyDoNotLockTheSameCodeInAnother() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String hash = data.pinHash();
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            jdbc.update("""
                    insert into employee (company_id, branch_id, code, name, pin_hash, role)
                    values (?, ?, 'SHARED', 'X', ?, 'EMPLOYEE')
                    """, t.companyId(), t.branchId(), hash);
        }
        for (int i = 0; i < 5; i++) {
            login(a.companyCode(), "SHARED", "11112222");
        }
        assertThat(login(a.companyCode(), "SHARED", TestData.PIN).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(b.companyCode(), "SHARED", TestData.PIN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void inactiveAccountsDoNotCountFailures() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set active = false where id = ?", u.id());
        for (int i = 0; i < 7; i++) {
            assertThat(login(t.companyCode(), u.code(), "11112222").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                u.id())).isZero();
        assertThat(jdbc.queryForObject("select locked_until is null from employee where id = ?", Boolean.class,
                u.id())).isTrue();
    }

    @Test
    void inactiveCompanyDoesNotCountFailuresAndGivesTheGenericBody() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        String expected = bodyText(login(t.companyCode(), u.code(), "11112222"));
        jdbc.update("update company set active = false where id = ?", t.companyId());
        MvcResult correct = login(t.companyCode(), u.code(), u.pin());
        MvcResult incorrect = login(t.companyCode(), u.code(), "11112222");
        assertThat(correct.getResponse().getStatus()).isEqualTo(401);
        assertThat(bodyText(correct)).isEqualTo(expected);
        assertThat(bodyText(incorrect)).isEqualTo(expected);
        // Only the first (active-company) failure counted.
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                u.id())).isEqualTo(1);
    }

    @Test
    void successfulLoginDoesNotExposeLockFieldsOrHash() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        String body = bodyText(login(t.companyCode(), u.code(), u.pin()));
        assertThat(body).doesNotContain("attempts").doesNotContain("locked").doesNotContain("tokenVersion")
                .doesNotContain("companyId").doesNotContain("$2");
    }
}
