package cr.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.support.AdminClient;
import cr.attendance.support.TestData;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * L-4: two per-admin write budgets (general 3, review 4 here), checked in the security chain before binding.
 * Each test uses fresh admins, so the budgets of one test never leak into another.
 */
@SpringBootTest(properties = {"app.admin.rate-limit.write.max-attempts=3", "app.admin.rate-limit.review-write.max-attempts=4"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles("test")
class AdminWriteRateLimitIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    private static Map<String, Object> branch(String name) {
        return Map.of("name", name, "latitude", 9.9, "longitude", -84.0, "radiusMeters", 100);
    }

    private long pendingEntry(TestData.Tenant t) {
        long employee = admins.employee(t);
        return admins.entry(t, employee, "CLOCK_IN", LocalDate.now(), Instant.now(), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
    }

    private void approve(String token, long entry, int expectedStatus) throws Exception {
        admins.post(token, "/api/admin/time-entries/" + entry + "/approve",
                Map.of("reason", "Verificado con la foto", "expectedStatus", "PENDING_REVIEW"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void theFourthGeneralWriteIsRefusedWithRateLimitedAndNothingIsWritten() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        AdminClient.Admin other = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            admins.post(admin.token(), "/api/admin/branches", branch("Limite " + i + " " + System.nanoTime())).andExpect(status().isCreated());
        }
        int branches = jdbc.queryForObject("select count(*) from branch where company_id = ?", Integer.class, t.companyId());
        int audits = jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class, t.companyId());
        admins.post(admin.token(), "/api/admin/branches", branch("Cuarta " + System.nanoTime()))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("select count(*) from branch where company_id = ?", Integer.class, t.companyId())).isEqualTo(branches);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class, t.companyId())).isEqualTo(audits);
        // Another admin has a budget of their own.
        admins.post(other.token(), "/api/admin/branches", branch("Otro " + System.nanoTime())).andExpect(status().isCreated());
    }

    @Test
    void putIsCountedInTheGeneralBudgetToo() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long branch = data.branch(t.companyId());
        for (int i = 0; i < 3; i++) {
            int version = jdbc.queryForObject("select version from branch where id = ?", Integer.class, branch);
            Map<String, Object> body = new java.util.HashMap<>(branch("Editada " + i + " " + System.nanoTime()));
            body.put("expectedVersion", version);
            admins.put(admin.token(), "/api/admin/branches/" + branch, body).andExpect(status().isOk());
        }
        String nameBefore = jdbc.queryForObject("select name from branch where id = ?", String.class, branch);
        Map<String, Object> fourth = new java.util.HashMap<>(branch("Cuarta " + System.nanoTime()));
        fourth.put("expectedVersion", jdbc.queryForObject("select version from branch where id = ?", Integer.class, branch));
        admins.put(admin.token(), "/api/admin/branches/" + branch, fourth)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertThat(jdbc.queryForObject("select name from branch where id = ?", String.class, branch)).isEqualTo(nameBefore);
    }

    @Test
    void rejectCorrectAndAdminCreateUseTheReviewBudgetWhileTheGeneralOneIsSpent() throws Exception {
        ZoneId cr = ZoneId.of("America/Costa_Rica");
        LocalDate day = LocalDate.now(cr).minusDays(1);
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            admins.post(admin.token(), "/api/admin/branches", branch("Gastar " + i + " " + System.nanoTime())).andExpect(status().isCreated());
        }
        admins.post(admin.token(), "/api/admin/branches", branch("Gastada " + System.nanoTime())).andExpect(status().isTooManyRequests());

        long rejected = pendingEntry(t);
        admins.post(admin.token(), "/api/admin/time-entries/" + rejected + "/reject",
                Map.of("reason", "No coincide con la foto", "expectedStatus", "PENDING_REVIEW")).andExpect(status().isOk());

        long employee = admins.employee(t);
        long toCorrect = admins.entry(t, employee, "CLOCK_IN", day, day.atTime(12, 0).atZone(cr).toInstant(), "VALID", null, 0);
        String effective = admins.json(admins.get(admin.token(), "/api/admin/time-entries/" + toCorrect)).get("effectiveTime").asText();
        admins.post(admin.token(), "/api/admin/time-entries/" + toCorrect + "/correct",
                Map.of("reason", "Hora del teléfono incorrecta", "expectedStatus", "VALID", "expectedEffectiveTime", effective,
                        "localTime", day + "T08:03")).andExpect(status().isOk());

        long other = admins.employee(t);
        admins.post(admin.token(), "/api/admin/time-entries",
                Map.of("employeeId", other, "type", "CLOCK_IN", "workDate", day.toString(), "localTime", day + "T07:00",
                        "reason", "Falla de la cámara o de la ubicación")).andExpect(status().isCreated());
        // All three came out of the review budget (4): one left, then it runs out.
        approve(admin.token(), pendingEntry(t), 200);
        approve(admin.token(), pendingEntry(t), 429);
    }

    @Test
    void anInvalidWriteCountsAndReadsAreNeverLimited() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            admins.postRaw(admin.token(), "/api/admin/branches", "{}").andExpect(status().isBadRequest());
        }
        admins.postRaw(admin.token(), "/api/admin/branches", "{}").andExpect(status().isTooManyRequests());
        for (int i = 0; i < 8; i++) {
            admins.get(admin.token(), "/api/admin/branches").andExpect(status().isOk());
        }
    }

    @Test
    void reviewActionsHaveTheirOwnBudgetAndDoNotUseTheGeneralOne() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            admins.post(admin.token(), "/api/admin/branches", branch("Agotar " + i + " " + System.nanoTime())).andExpect(status().isCreated());
        }
        admins.post(admin.token(), "/api/admin/branches", branch("Agotada " + System.nanoTime())).andExpect(status().isTooManyRequests());
        // The general budget is spent; four review actions still work and the fifth is refused.
        for (int i = 0; i < 4; i++) {
            approve(admin.token(), pendingEntry(t), 200);
        }
        long last = pendingEntry(t);
        approve(admin.token(), last, 429);
        assertThat(admins.adjustmentCount(last)).isZero();
    }

    @Test
    void anExhaustedReviewBudgetDoesNotBlockManagementWrites() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 4; i++) {
            approve(admin.token(), pendingEntry(t), 200);
        }
        approve(admin.token(), pendingEntry(t), 429);
        admins.post(admin.token(), "/api/admin/branches", branch("Fresca " + System.nanoTime())).andExpect(status().isCreated());
        // The entry detail is a read: not counted by either write budget.
        long entry = pendingEntry(t);
        admins.get(admin.token(), "/api/admin/time-entries/" + entry).andExpect(status().isOk());
    }

    @Test
    void anEmployeeTokenStillGetsAnEmptyForbiddenAndNeverConsumesABudget() throws Exception {
        TestData.Tenant t = data.tenant();
        String token = admins.tokenFor(t, data.user(t, "EMPLOYEE"));
        for (int i = 0; i < 6; i++) {
            String content = admins.post(token, "/api/admin/branches", branch("No " + i)).andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();
            assertThat(content).isEmpty();
        }
    }

    private org.springframework.test.web.servlet.ResultActions encodedPost(String token, String uri) throws Exception {
        return mockMvc.perform(post(URI.create(uri)).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{}"));
    }

    /** M-1: a percent-encoded path reaches the controller, so it must be counted and refused like the plain one. */
    @Test
    void aPercentEncodedAdminPathConsumesTheGeneralBudget() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            encodedPost(admin.token(), "/api/%61dmin/branches").andExpect(status().isBadRequest());
        }
        encodedPost(admin.token(), "/api/%61dmin/branches")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // The plain path shares the same budget.
        admins.postRaw(admin.token(), "/api/admin/branches", "{}").andExpect(status().isTooManyRequests());
    }

    @Test
    void aPercentEncodedReviewPathConsumesTheReviewBudget() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long entry = pendingEntry(t);
        for (int i = 0; i < 4; i++) {
            encodedPost(admin.token(), "/api/admin/time-entries/" + entry + "/%61pprove");
        }
        encodedPost(admin.token(), "/api/admin/time-entries/" + entry + "/%61pprove")
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        approve(admin.token(), pendingEntry(t), 429);
        // The general budget was not touched.
        admins.post(admin.token(), "/api/admin/branches", branch("Intacta " + System.nanoTime())).andExpect(status().isCreated());
    }

    @Test
    void theRefusalFollowsAcceptLanguage() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (int i = 0; i < 3; i++) {
            admins.postRaw(admin.token(), "/api/admin/branches", "{}");
        }
        mockMvc.perform(post("/api/admin/branches").header("Authorization", "Bearer " + admin.token())
                        .header("Accept-Language", "en").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.title").value("Too many actions"));
        mockMvc.perform(post("/api/admin/branches").header("Authorization", "Bearer " + admin.token())
                        .header("Accept-Language", "es").contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.title").value("Demasiadas acciones"));
    }
}
