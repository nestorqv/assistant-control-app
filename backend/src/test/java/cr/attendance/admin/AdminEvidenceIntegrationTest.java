package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@Import(MutableClockConfig.class)
class AdminEvidenceIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private AdminClient admins;

    @Autowired
    private CheckInClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startOfWorkDay() {
        clock.advanceToNext(CR, LocalTime.of(8, 0));
    }

    private long clockIn(CheckInClient.Actor actor) throws Exception {
        return client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated())).get("id").asLong();
    }

    @Test
    void theDetailCarriesTheCoordinatesTheBranchAndTheHistoryWithAdminNames() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);
        AdminClient.Admin admin = admins.admin(actor.tenant());
        jdbc.update("update employee set name = 'Ana Mora' where id = ?", admin.id());
        admins.post(admin.token(), "/api/admin/time-entries/" + id + "/reject",
                Map.of("reason", "La foto no corresponde al empleado", "expectedStatus", "VALID"))
                .andExpect(status().isOk());

        JsonNode detail = admins.json(admins.get(admin.token(), "/api/admin/time-entries/" + id)
                .andExpect(status().isOk()));
        assertThat(detail.get("location").get("latitude").asDouble()).isEqualTo(9.9325);
        assertThat(detail.get("location").get("longitude").asDouble()).isEqualTo(-84.0796);
        assertThat(detail.get("branch").get("radiusMeters").asInt()).isEqualTo(100);
        assertThat(detail.get("hasPhoto").asBoolean()).isTrue();
        assertThat(detail.get("source").asText()).isEqualTo("MOBILE");
        assertThat(detail.get("adjustments")).hasSize(1);
        assertThat(detail.get("adjustments").get(0).get("adminName").asText()).isEqualTo("Ana Mora");
        assertThat(detail.toString()).doesNotContain("clientUuid").doesNotContain("storageKey");
    }

    @Test
    void everySuccessfulPhotoViewIsAuditedAndAMissOrAnEmployeeRouteIsNot() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);
        AdminClient.Admin admin = admins.admin(actor.tenant());
        long companyId = actor.companyId();

        MvcResult photo = admins.get(admin.token(), "/api/admin/time-entries/" + id + "/photo")
                .andExpect(status().isOk()).andReturn();
        assertThat(photo.getResponse().getHeader("Cache-Control")).contains("no-store");
        admins.get(admin.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk());
        admins.get(admin.token(), "/api/admin/time-entries/9999999/photo").andExpect(status().isNotFound());
        client.get(actor, "/api/time-entries/" + id + "/photo").andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select actor_id, target_type, target_id, details from audit_log where company_id = ? and action = 'PHOTO_VIEW'",
                companyId);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(((Number) r.get("actor_id")).longValue()).isEqualTo(admin.id());
            assertThat(r.get("target_type")).isEqualTo("TIME_ENTRY");
            assertThat(((Number) r.get("target_id")).longValue()).isEqualTo(id);
            assertThat(String.valueOf(r.get("details"))).isEqualTo("entry=" + id);
        });
    }

    @Test
    void aManualEntryHasNoPhoto() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = admins.employee(t);
        var today = clock.instant().atZone(CR).toLocalDate();
        JsonNode created = admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                Map.of("employeeId", employee, "type", "CLOCK_IN", "workDate", today.toString(), "localTime",
                        today + "T07:00", "reason", "Falla de la cámara o de la ubicación"))
                .andExpect(status().isCreated()));
        admins.get(admin.token(), "/api/admin/time-entries/" + created.get("id").asLong() + "/photo")
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where company_id = ?", Integer.class,
                t.companyId())).isZero();
    }

    @Test
    void auditRowsCannotBeChangedOrDeletedEvenWithSql() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = clockIn(actor);
        AdminClient.Admin admin = admins.admin(actor.tenant());
        admins.get(admin.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk());

        for (String sql : List.of("update audit_log set details = 'x'", "delete from audit_log",
                "truncate table audit_log")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.execute(sql))
                    .hasMessageContaining("append-only table: audit_log");
        }
    }
}
