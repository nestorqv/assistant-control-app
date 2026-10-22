package cr.attendance.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AdminEmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void employeeTokenIsForbidden() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        mockMvc.perform(get("/api/admin/employees").header("Authorization", bearer(data.login(t, u))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSeesOnlyItsOwnCompany() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser adminA = data.user(a, "ADMIN");
        TestData.TestUser empA = data.user(a, "EMPLOYEE");
        TestData.TestUser empB = data.user(b, "EMPLOYEE");

        String json = mockMvc.perform(get("/api/admin/employees")
                        .header("Authorization", bearer(data.login(a, adminA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("\"id\":" + adminA.id(), "\"id\":" + empA.id());
        assertThat(json).doesNotContain("\"id\":" + empB.id());
        assertThat(json).doesNotContainIgnoringCase("pinHash").doesNotContain("failedAttempts")
                .doesNotContain("lockedUntil").doesNotContain("$2");
    }

    @Test
    void employeeOfAnotherCompanyIsNotFound() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser adminA = data.user(a, "ADMIN");
        TestData.TestUser empB = data.user(b, "EMPLOYEE");
        String token = bearer(data.login(a, adminA));

        mockMvc.perform(get("/api/admin/employees/" + empB.id()).header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/employees/" + adminA.id()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void roleChangeInTheDatabaseAppliesToAnIssuedToken() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser admin = data.user(t, "ADMIN");
        String token = bearer(data.login(t, admin));
        mockMvc.perform(get("/api/admin/employees").header("Authorization", token)).andExpect(status().isOk());

        // The CHECK requires a branch for non-admins, so set it together with the demotion.
        jdbc.update("update employee set role = 'EMPLOYEE', branch_id = ? where id = ?", t.branchId(), admin.id());
        mockMvc.perform(get("/api/admin/employees").header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void typeMismatchOnPathVariableIsBadRequestWithCodeAndDoesNotEchoTheInput() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser admin = data.user(t, "ADMIN");
        String response = mockMvc.perform(get("/api/admin/employees/{id}", "abc<script>")
                        .header("Authorization", bearer(data.login(t, admin))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("abc").doesNotContain("script").doesNotContain("Failed to convert");
    }
}
