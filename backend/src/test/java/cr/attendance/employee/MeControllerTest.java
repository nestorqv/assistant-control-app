package cr.attendance.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void meReturnsTheCallersOwnProfileAndCompanyZone() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        TestData.TestUser other = data.user(t, "EMPLOYEE");
        String token = data.login(t, u);

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(u.id()))
                .andExpect(jsonPath("$.code").value(u.code()))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.consentPending").value(true))
                .andExpect(jsonPath("$.company.timeZone").value("America/Costa_Rica"))
                .andExpect(jsonPath("$.pinHash").doesNotExist());
        assertThat(other.id()).isNotEqualTo(u.id());
    }

    @Test
    void consentIsIdempotentAndKeepsTheFirstDate() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        String token = data.login(t, u);

        mockMvc.perform(post("/api/me/consent").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        String first = jdbc.queryForObject("select consent_at::text from employee where id = ?",
                String.class, u.id());
        assertThat(first).isNotNull();

        mockMvc.perform(post("/api/me/consent").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        String second = jdbc.queryForObject("select consent_at::text from employee where id = ?",
                String.class, u.id());
        assertThat(second).isEqualTo(first);

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.consentPending").value(false));
    }

    @Test
    void consentRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/me/consent")).andExpect(status().isUnauthorized());
    }
}
