package cr.attendance.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.JwtTestTokens;
import cr.attendance.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class JwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JwtTestTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData.Tenant tenant;
    private TestData.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        user = data.user(tenant, "EMPLOYEE");
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
    }

    private void isUnauthorizedWithoutChallenge(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void tokenFromLoginWorks() throws Exception {
        me(data.login(tenant, user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id()));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void handBuiltValidTokenWorks() throws Exception {
        me(tokens.valid(user.id(), tenant.companyId(), "EMPLOYEE", 0)).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        isUnauthorizedWithoutChallenge(me(tokens.expired(user.id(), tenant.companyId(), "EMPLOYEE", 0)));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        isUnauthorizedWithoutChallenge(me(tokens.otherIssuer(user.id(), tenant.companyId(), "EMPLOYEE", 0)));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        isUnauthorizedWithoutChallenge(me(tokens.otherKey(user.id(), tenant.companyId(), "EMPLOYEE", 0)));
    }

    @Test
    void algNoneIsRejected() throws Exception {
        isUnauthorizedWithoutChallenge(me(tokens.algNone(user.id(), tenant.companyId(), "EMPLOYEE", 0)));
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        String valid = tokens.valid(user.id(), tenant.companyId(), "EMPLOYEE", 0);
        isUnauthorizedWithoutChallenge(me(tokens.withTamperedPayload(valid, other.companyId())));
    }

    @Test
    void missingCompanyIdClaimIsRejected() throws Exception {
        isUnauthorizedWithoutChallenge(me(tokens.withoutCompanyId(user.id(), "EMPLOYEE", 0)));
    }

    @Test
    void employeeDeactivatedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, user);
        jdbc.update("update employee set active = false where id = ?", user.id());
        isUnauthorizedWithoutChallenge(me(token));
    }

    @Test
    void companyDeactivatedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, user);
        jdbc.update("update company set active = false where id = ?", tenant.companyId());
        isUnauthorizedWithoutChallenge(me(token));
    }

    @Test
    void tokenVersionBumpedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, user);
        jdbc.update("update employee set token_version = token_version + 1 where id = ?", user.id());
        isUnauthorizedWithoutChallenge(me(token));
    }

    @Test
    void companyIdClaimNotMatchingTheRowIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        isUnauthorizedWithoutChallenge(me(tokens.valid(user.id(), other.companyId(), "EMPLOYEE", 0)));
    }
}
