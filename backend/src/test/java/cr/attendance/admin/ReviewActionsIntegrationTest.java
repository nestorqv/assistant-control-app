package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class ReviewActionsIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final String REASON = "Verificado con la foto";

    @Autowired
    private AdminClient admins;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData.Tenant tenant;
    private AdminClient.Admin admin;
    private long employeeId;

    @BeforeEach
    void eightInTheEvening() throws Exception {
        clock.advanceToNext(CR, LocalTime.of(20, 0));
        tenant = data.tenant();
        admin = admins.admin(tenant);
        employeeId = admins.employee(tenant);
        data.schedule(tenant.companyId(), employeeId, today().getDayOfWeek().getValue(), LocalTime.of(8, 0),
                LocalTime.of(17, 0), 5);
    }

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    private Instant local(String time) {
        return today().atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }

    private String localTime(String time) {
        return today() + "T" + time;
    }

    private long pendingClockIn() {
        return admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:17"), "PENDING_REVIEW",
                "SYNCED_LATE", 17);
    }

    private Map<String, Object> action(String reason, String expectedStatus) {
        return Map.of("reason", reason, "expectedStatus", expectedStatus);
    }

    private Map<String, Object> correction(long id, String time, String expectedStatus) throws Exception {
        String effective = admins.json(admins.get(admin.token(), "/api/admin/time-entries/" + id)).get("effectiveTime")
                .asText();
        return Map.of("reason", "Hora del teléfono incorrecta", "expectedStatus", expectedStatus,
                "expectedEffectiveTime", effective, "localTime", localTime(time));
    }

    private JsonNode post(long id, String verb, Object body) throws Exception {
        return admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + id + "/" + verb, body)
                .andExpect(status().isOk()));
    }

    private String serverTime(long id) {
        return jdbc.queryForObject("select server_time::text from time_entry where id = ?", String.class, id);
    }

    @Test
    void approveMakesTheEntryValidKeepsTheReasonsAndWritesOneAdjustment() throws Exception {
        long id = pendingClockIn();
        JsonNode body = post(id, "approve", action(REASON, "PENDING_REVIEW"));

        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("reviewReasons").get(0).asText()).isEqualTo("SYNCED_LATE");
        assertThat(body.toString()).doesNotContain("clientUuid");
        JsonNode adjustment = body.get("adjustments").get(0);
        assertThat(body.get("adjustments")).hasSize(1);
        assertThat(adjustment.get("action").asText()).isEqualTo("APPROVE");
        assertThat(adjustment.get("field").asText()).isEqualTo("status");
        assertThat(adjustment.get("oldValue").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(adjustment.get("newValue").asText()).isEqualTo("VALID");
        assertThat(adjustment.get("reason").asText()).isEqualTo(REASON);
        assertThat(adjustment.get("selfApproved").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select admin_id from adjustment where time_entry_id = ?", Long.class, id))
                .isEqualTo(admin.id());
    }

    @Test
    void approvingAnEntryThatIsAlreadyValidIsANoOp() throws Exception {
        long id = admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
        String code = admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve",
                action(REASON, "VALID")).andExpect(status().isConflict())).get("code").asText();
        assertThat(code).isEqualTo("INVALID_STATUS_CHANGE");
        assertThat(admins.adjustmentCount(id)).isZero();
    }

    @Test
    void rejectFromValidAndThenUndoByApproving() throws Exception {
        long id = admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
        JsonNode rejected = post(id, "reject", action("La foto no corresponde al empleado", "VALID"));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        JsonNode undone = post(id, "approve", action("Rechazo hecho por error", "REJECTED"));
        assertThat(undone.get("status").asText()).isEqualTo("VALID");
        assertThat(undone.get("adjustments")).hasSize(2);
    }

    @Test
    void correctingAClockInRecomputesLatenessAndWritesThreeRowsWithoutTouchingTheReceiptTime() throws Exception {
        long id = pendingClockIn();
        String receipt = serverTime(id);

        JsonNode body = post(id, "correct", correction(id, "08:03", "PENDING_REVIEW"));

        assertThat(body.get("status").asText()).isEqualTo("VALID");
        assertThat(body.get("lateMinutes").asInt()).isZero();
        assertThat(Instant.parse(body.get("effectiveTime").asText())).isEqualTo(local("08:03"));
        assertThat(Instant.parse(body.get("serverTime").asText())).isEqualTo(local("08:17"));
        assertThat(serverTime(id)).isEqualTo(receipt);
        List<String> fields = jdbc.queryForList("select field from adjustment where time_entry_id = ? order by id",
                String.class, id);
        assertThat(fields).containsExactly("effective_time", "late_minutes", "status");
        assertThat(jdbc.queryForObject("select new_value from adjustment where time_entry_id = ? and field = 'effective_time'",
                String.class, id)).isEqualTo(today() + "T08:03-06:00");
        assertThat(jdbc.queryForObject("select work_date = ? from time_entry where id = ?", Boolean.class,
                java.sql.Date.valueOf(today()), id)).isTrue();
    }

    @Test
    void correctingAClockOutShowsInTheDashboardAndInTheEmployeesToday() throws Exception {
        admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
        long out = admins.entry(tenant, employeeId, "CLOCK_OUT", today(), local("17:00"), "VALID", null, null);

        JsonNode body = post(out, "correct", correction(out, "17:30", "VALID"));
        assertThat(body.get("adjustments")).hasSize(1);

        JsonNode dashboard = admins.json(admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()));
        assertThat(dashboard.get("rows").get(0).get("workedMinutes").asInt()).isEqualTo(570);
        assertThat(dashboard.get("rows").get(0).get("clockOut").get("adjusted").asBoolean()).isTrue();

        long userId = employeeId;
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, userId);
        String token = admins.tokenFor(tenant, new TestData.TestUser(userId, code, TestData.PIN));
        JsonNode today = admins.json(admins.get(token, "/api/time-entries/today").andExpect(status().isOk()));
        assertThat(today.get("workedMinutes").asInt()).isEqualTo(570);
        JsonNode clockOut = today.get("clockOut");
        assertThat(Instant.parse(clockOut.get("effectiveTime").asText())).isEqualTo(local("17:30"));
        assertThat(Instant.parse(clockOut.get("serverTime").asText())).isEqualTo(local("17:00"));
        assertThat(clockOut.get("adjusted").asBoolean()).isTrue();
        // An approval alone is not an adjustment of the time the employee sees.
        assertThat(today.get("clockIn").get("adjusted").asBoolean()).isFalse();
    }

    @Test
    void aStaleExpectedStatusOrEffectiveTimeIsEntryChanged() throws Exception {
        long id = pendingClockIn();
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve",
                action(REASON, "VALID")).andExpect(status().isConflict())).get("code").asText())
                .isEqualTo("ENTRY_CHANGED");
        Map<String, Object> stale = Map.of("reason", REASON, "expectedStatus", "PENDING_REVIEW",
                "expectedEffectiveTime", local("08:00").toString(), "localTime", localTime("08:03"));
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + id + "/correct", stale)
                .andExpect(status().isConflict())).get("code").asText()).isEqualTo("ENTRY_CHANGED");
        assertThat(admins.adjustmentCount(id)).isZero();
    }

    @Test
    void twoAdminsApprovingTheSameEntryGetOneOkAndOneConflict() throws Exception {
        long id = pendingClockIn();
        AdminClient.Admin second = admins.admin(tenant);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve",
                            action(REASON, "PENDING_REVIEW")).andReturn().getResponse().getStatus(),
                    () -> admins.post(second.token(), "/api/admin/time-entries/" + id + "/approve",
                            action(REASON, "PENDING_REVIEW")).andReturn().getResponse().getStatus());
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(admins.adjustmentCount(id)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theReasonMustBeFiveToFiveHundredCharactersAfterTrimming() throws Exception {
        long id = pendingClockIn();
        for (String bad : List.of("", "    ", " abcd ", "x".repeat(501), "con\nsalto de linea")) {
            admins.post(admin.token(), "/api/admin/time-entries/" + id + "/approve", action(bad, "PENDING_REVIEW"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(admins.adjustmentCount(id)).isZero();
        post(id, "approve", action("x".repeat(500), "PENDING_REVIEW"));
    }

    @Test
    void impossibleTimesAreRejectedAndSameMinuteIsANoOp() throws Exception {
        admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
        long out = admins.entry(tenant, employeeId, "CLOCK_OUT", today(), local("17:00"), "VALID", null, null);
        // In the future (it is 20:00), before the clock-in, and more than 16 h after it.
        for (String time : List.of("23:00", "07:59", "08:00")) {
            assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + out + "/correct",
                    correction(out, time, "VALID")).andExpect(status().isBadRequest())).get("code").asText())
                    .isEqualTo("INVALID_ENTRY_TIME");
        }
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + out + "/correct",
                correction(out, "17:00", "VALID")).andExpect(status().isConflict())).get("code").asText())
                .isEqualTo("INVALID_STATUS_CHANGE");
        assertThat(admins.adjustmentCount(out)).isZero();
    }

    @Test
    void aClockInCannotBeMovedPastTheClockOut() throws Exception {
        long in = admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
        admins.entry(tenant, employeeId, "CLOCK_OUT", today(), local("17:00"), "VALID", null, null);
        admins.post(admin.token(), "/api/admin/time-entries/" + in + "/correct", correction(in, "17:30", "VALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAdminApprovingTheirOwnEntryIsFlaggedAsSelfApproved() throws Exception {
        long adminEmployee = data.employee(tenant.companyId(), tenant.branchId(), "ADMIN");
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, adminEmployee);
        String token = admins.tokenFor(tenant, new TestData.TestUser(adminEmployee, code, TestData.PIN));
        long id = admins.entry(tenant, adminEmployee, "CLOCK_IN", today(), local("08:30"), "PENDING_REVIEW",
                "OUTSIDE_GEOFENCE", 30);

        JsonNode before = admins.json(admins.get(token, "/api/admin/time-entries/" + id).andExpect(status().isOk()));
        assertThat(before.get("ownEntry").asBoolean()).isTrue();
        JsonNode body = admins.json(admins.post(token, "/api/admin/time-entries/" + id + "/approve",
                action(REASON, "PENDING_REVIEW")).andExpect(status().isOk()));
        assertThat(body.get("adjustments").get(0).get("selfApproved").asBoolean()).isTrue();
    }

    @Test
    void anotherTenantsEntryIs404AndAnEmployeeTokenIs403() throws Exception {
        long id = pendingClockIn();
        TestData.Tenant other = data.tenant();
        AdminClient.Admin foreign = admins.admin(other);
        String content = admins.post(foreign.token(), "/api/admin/time-entries/" + id + "/approve",
                action(REASON, "PENDING_REVIEW")).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString();
        assertThat(content).contains("NOT_FOUND");
        admins.get(foreign.token(), "/api/admin/time-entries/" + id).andExpect(status().isNotFound());

        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
        String employeeToken = admins.tokenFor(tenant, new TestData.TestUser(employeeId, code, TestData.PIN));
        admins.post(employeeToken, "/api/admin/time-entries/" + id + "/approve", action(REASON, "PENDING_REVIEW"))
                .andExpect(status().isForbidden());
        assertThat(admins.adjustmentCount(id)).isZero();
    }

    @Test
    void anUnknownOldLatenessIsStoredAsSqlNullNeverAsTheTextNull() throws Exception {
        // Recorded while the employee had no schedule (late_minutes is NULL); the schedule exists now.
        long id = admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:17"), "VALID", null, null);
        post(id, "correct", correction(id, "08:03", "VALID"));
        assertThat(jdbc.queryForObject(
                "select old_value is null from adjustment where time_entry_id = ? and field = 'late_minutes'",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from adjustment where time_entry_id = ? and 'null' in (old_value, new_value)",
                Integer.class, id)).isZero();
    }
}
