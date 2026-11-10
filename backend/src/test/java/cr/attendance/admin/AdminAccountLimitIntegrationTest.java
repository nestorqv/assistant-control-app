package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.TestData;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Employee creation and PIN reset share one account budget; it is checked before any PIN is generated. */
@SpringBootTest(properties = "app.admin.rate-limit.account.max-attempts=2")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles("test")
class AdminAccountLimitIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theThirdCreateOrResetIsRefusedWithRateLimitedAndNothingIsCreated() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        TestData.TestUser target = data.user(t, "EMPLOYEE");
        for (int i = 0; i < 2; i++) {
            admins.post(admin.token(), "/api/admin/employees",
                    Map.of("code", data.randomCode("L"), "name", "Persona " + i, "role", "EMPLOYEE", "branchId", t.branchId()))
                    .andExpect(status().isCreated());
        }
        int employees = jdbc.queryForObject("select count(*) from employee where company_id = ?", Integer.class, t.companyId());
        String hash = jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target.id());

        admins.postEmpty(admin.token(), "/api/admin/employees/" + target.id() + "/pin-reset")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        admins.post(admin.token(), "/api/admin/employees",
                Map.of("code", data.randomCode("L"), "name", "Una Mas", "role", "EMPLOYEE", "branchId", t.branchId()))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("select count(*) from employee where company_id = ?", Integer.class, t.companyId())).isEqualTo(employees);
        assertThat(jdbc.queryForObject("select pin_hash from employee where id = ?", String.class, target.id())).isEqualTo(hash);
        // Other writes use their own budget.
        admins.postEmpty(admin.token(), "/api/admin/employees/" + target.id() + "/unlock").andExpect(status().isOk());
    }
}
