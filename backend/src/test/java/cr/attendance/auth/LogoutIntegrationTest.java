package cr.attendance.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LogoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

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

    private ResultActions logout(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token));
    }

    @Test
    void logoutReturns204AndTheSameTokenIsThenRejected() throws Exception {
        String token = data.login(tenant, user);
        me(token).andExpect(status().isOk());

        logout(token).andExpect(status().isNoContent());

        me(token).andExpect(status().isUnauthorized());
    }

    @Test
    void aFreshLoginAfterLogoutWorks() throws Exception {
        String old = data.login(tenant, user);
        logout(old).andExpect(status().isNoContent());

        String fresh = data.login(tenant, user);

        me(fresh).andExpect(status().isOk());
        me(old).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSecondLogoutWithTheOldTokenIsUnauthorized() throws Exception {
        String token = data.login(tenant, user);
        logout(token).andExpect(status().isNoContent());

        logout(token).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherEmployeeOfTheSameCompanyIsUnaffected() throws Exception {
        TestData.TestUser colleague = data.user(tenant, "EMPLOYEE");
        String colleagueToken = data.login(tenant, colleague);
        String token = data.login(tenant, user);

        logout(token).andExpect(status().isNoContent());

        me(colleagueToken).andExpect(status().isOk());
    }

    @Test
    void theSameEmployeeCodeInAnotherCompanyIsUnaffected() throws Exception {
        TestData.Tenant otherTenant = data.tenant();
        long otherId = jdbc.queryForObject("""
                insert into employee (company_id, branch_id, code, name, job_title, pin_hash, role)
                values (?, ?, ?, 'Persona de prueba', 'Puesto', ?, 'EMPLOYEE') returning id
                """, Long.class, otherTenant.companyId(), otherTenant.branchId(), user.code(), data.pinHash());
        TestData.TestUser sameCode = new TestData.TestUser(otherId, user.code(), TestData.PIN);
        String otherToken = data.login(otherTenant, sameCode);
        String token = data.login(tenant, user);

        logout(token).andExpect(status().isNoContent());

        me(otherToken).andExpect(status().isOk());
    }
}
