package cr.attendance.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cr.attendance.employee.EmployeeRepository;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tenant isolation through the API and through the scoped repository methods. */
@IntegrationTest
class TenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmployeeRepository employees;

    private MvcResult getAdmin(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
    }

    @Test
    void anotherTenantsEmployeeLooksExactlyLikeAMissingOne() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser adminA = data.user(a, "ADMIN");
        TestData.TestUser empB = data.user(b, "EMPLOYEE");
        String token = data.login(a, adminA);

        MvcResult otherTenant = getAdmin("/api/admin/employees/" + empB.id(), token);
        MvcResult missing = getAdmin("/api/admin/employees/" + (empB.id() + 1_000_000), token);

        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        // "instance" echoes the requested path (the caller's own input); everything else must be identical.
        String withoutInstance = "\"instance\":\"[^\"]*\",?";
        String bodyOther = otherTenant.getResponse().getContentAsString().replaceAll(withoutInstance, "");
        String bodyMissing = missing.getResponse().getContentAsString().replaceAll(withoutInstance, "");
        assertThat(bodyOther).isEqualTo(bodyMissing).contains("\"code\":\"NOT_FOUND\"")
                .doesNotContain(empB.code());
    }

    @Test
    void bothAdminsSeeOnlyTheirOwnEmployeesAndNeverEachOthers() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser adminA = data.user(a, "ADMIN");
        TestData.TestUser adminB = data.user(b, "ADMIN");
        TestData.TestUser empA = data.user(a, "EMPLOYEE");
        TestData.TestUser empB = data.user(b, "EMPLOYEE");

        String bodyA = getAdmin("/api/admin/employees", data.login(a, adminA)).getResponse().getContentAsString();
        String userB = getAdmin("/api/admin/employees", data.login(b, adminB)).getResponse().getContentAsString();

        assertThat(bodyA).contains("\"id\":" + empA.id()).doesNotContain("\"id\":" + empB.id())
                .doesNotContain("\"id\":" + adminB.id());
        assertThat(userB).contains("\"id\":" + empB.id()).doesNotContain("\"id\":" + empA.id())
                .doesNotContain("\"id\":" + adminA.id());
    }

    @Test
    void listIncludesInactiveEmployeesOfTheOwnTenantWithTheirFlag() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.TestUser adminA = data.user(a, "ADMIN");
        TestData.TestUser deactivated = data.user(a, "EMPLOYEE");
        jdbc.update("update employee set active = false where id = ?", deactivated.id());
        String json = getAdmin("/api/admin/employees/" + deactivated.id(), data.login(a, adminA)).getResponse()
                .getContentAsString();
        assertThat(json).contains("\"active\":false");
    }

    @Test
    void nonNumericOrNegativeIdsNeverLeakInternals() throws Exception {
        TestData.Tenant a = data.tenant();
        String token = data.login(a, data.user(a, "ADMIN"));
        MvcResult bodyText = getAdmin("/api/admin/employees/abc", token);
        assertThat(bodyText.getResponse().getStatus()).isEqualTo(400);
        assertThat(bodyText.getResponse().getContentAsString()).doesNotContain("Exception")
                .doesNotContain("at cr.attendance").doesNotContain("MethodArgumentTypeMismatch");
        assertThat(getAdmin("/api/admin/employees/-5", token).getResponse().getStatus()).isEqualTo(404);
        assertThat(getAdmin("/api/admin/employees/99999999999999999999", token).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void consentOfOneTenantsEmployeeNeverTouchesAnother() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser uA = data.user(a, "EMPLOYEE");
        TestData.TestUser uB = data.user(b, "EMPLOYEE");
        mockMvc.perform(post("/api/me/consent").header("Authorization", "Bearer " + data.login(a, uA)))
                .andReturn();
        assertThat(jdbc.queryForObject("select consent_at is not null from employee where id = ?",
                Boolean.class, uA.id())).isTrue();
        assertThat(jdbc.queryForObject("select consent_at is null from employee where id = ?",
                Boolean.class, uB.id())).isTrue();
    }

    @Test
    void meReturnsTheCallersOwnCompanyOnly() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        jdbc.update("update company set name = 'Company A', time_zone = 'America/Panama' where id = ?",
                a.companyId());
        jdbc.update("update company set name = 'Company B' where id = ?", b.companyId());
        String json = getAdmin("/api/me", data.login(a, data.user(a, "EMPLOYEE"))).getResponse()
                .getContentAsString();
        assertThat(json).contains("Company A").contains("America/Panama").doesNotContain("Company B");
    }

    @Test
    void scopedRepositoryReadsReturnNothingForTheWrongCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser uB = data.user(b, "EMPLOYEE");
        assertThat(employees.findByIdAndCompanyId(uB.id(), a.companyId())).isEmpty();
        assertThat(employees.findByIdAndCompanyId(uB.id(), b.companyId())).isPresent();
        assertThat(employees.findByCompanyIdAndCode(a.companyId(), uB.code())).isEmpty();
        assertThat(employees.findAllByCompanyIdOrderByName(a.companyId()))
                .noneMatch(e -> e.getId().equals(uB.id()));
    }

    @Test
    void scopedRepositoryWritesAffectNoRowsOfAnotherCompany() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.TestUser uB = data.user(b, "EMPLOYEE");
        Instant now = Instant.now();

        assertThat(employees.recordFailedLogin(uB.id(), a.companyId(), 5, now.plus(Duration.ofMinutes(15)), now))
                .isZero();
        assertThat(employees.recordSuccessfulLogin(uB.id(), a.companyId(), now)).isZero();
        assertThat(employees.recordConsent(uB.id(), a.companyId(), now)).isZero();

        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                uB.id())).isZero();
        assertThat(jdbc.queryForObject("select consent_at is null from employee where id = ?",
                Boolean.class, uB.id())).isTrue();

        // Positive control: the same calls with the right company do change the row.
        assertThat(employees.recordFailedLogin(uB.id(), b.companyId(), 5, now.plus(Duration.ofMinutes(15)), now))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                uB.id())).isEqualTo(1);
    }

    @Test
    void recordFailedLoginLeavesALockedRowUntouchedAtTheRepositoryLevel() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        Instant now = Instant.now();
        jdbc.update("update employee set locked_until = ?, failed_attempts = 0 where id = ?",
                java.sql.Timestamp.from(now.plusSeconds(600)), u.id());
        assertThat(employees.recordFailedLogin(u.id(), t.companyId(), 5, now.plus(Duration.ofMinutes(15)), now))
                .isZero();
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                u.id())).isZero();
    }
}
