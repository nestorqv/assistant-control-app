package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** GET /api/admin/branches: only the caller's company, and activeEmployees counts only active staff. */
@IntegrationTest
class AdminBranchIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsOnlyTheOwnCompanysBranchesAndCountsOnlyActiveEmployees() throws Exception {
        TestData.Tenant mine = data.tenant();
        long secondBranch = data.branch(mine.companyId());
        AdminClient.Admin admin = admins.admin(mine);
        admins.employee(mine);
        admins.employee(mine);
        long retired = admins.employee(mine);
        jdbc.update("update employee set active = false where id = ?", retired);

        TestData.Tenant other = data.tenant();
        admins.employee(other);
        admins.employee(other);
        admins.employee(other);

        JsonNode body = admins.json(admins.get(admin.token(), "/api/admin/branches").andExpect(status().isOk()));

        List<Long> ids = new ArrayList<>();
        body.forEach(b -> ids.add(b.get("id").asLong()));
        assertThat(ids).containsExactlyInAnyOrder(mine.branchId(), secondBranch).doesNotContain(other.branchId());
        for (JsonNode branch : body) {
            long expected = branch.get("id").asLong() == mine.branchId() ? 2 : 0;
            assertThat(branch.get("activeEmployees").asLong()).isEqualTo(expected);
            assertThat(branch.get("active").asBoolean()).isTrue();
        }
    }
}
