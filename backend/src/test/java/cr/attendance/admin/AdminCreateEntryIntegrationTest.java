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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
class AdminCreateEntryIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final String REASON = "Olvidó marcar la salida";

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

    private Map<String, Object> body(long employee, String type, LocalDate workDate, String time) {
        return Map.of("employeeId", employee, "type", type, "workDate", workDate.toString(), "localTime",
                workDate + "T" + time, "reason", REASON);
    }

    @Test
    void aForgottenClockOutCompletesTheDayWithAManualEntryAndNoEvidence() throws Exception {
        admins.entry(tenant, employeeId, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);

        JsonNode created = admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                body(employeeId, "CLOCK_OUT", today(), "17:00")).andExpect(status().isCreated()));

        assertThat(created.get("source").asText()).isEqualTo("ADMIN");
        assertThat(created.get("status").asText()).isEqualTo("VALID");
        assertThat(Instant.parse(created.get("effectiveTime").asText())).isEqualTo(local("17:00"));
        assertThat(Instant.parse(created.get("serverTime").asText())).isEqualTo(clock.instant());
        assertThat(created.get("hasPhoto").asBoolean()).isFalse();
        assertThat(created.get("hasGps").asBoolean()).isFalse();
        assertThat(created.get("location").isNull()).isTrue();
        assertThat(created.toString()).doesNotContain("clientUuid");
        JsonNode adjustment = created.get("adjustments").get(0);
        assertThat(adjustment.get("action").asText()).isEqualTo("CREATE_ENTRY");
        assertThat(adjustment.get("field").asText()).isEqualTo("entry");
        assertThat(adjustment.get("oldValue").isNull()).isTrue();
        assertThat(adjustment.get("newValue").asText()).isEqualTo("CLOCK_OUT " + today() + "T17:00-06:00");
        assertThat(jdbc.queryForObject("select count(*) from evidence where time_entry_id = ?", Integer.class,
                created.get("id").asLong())).isZero();

        JsonNode dashboard = admins.json(admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()));
        assertThat(dashboard.get("rows").get(0).get("dayStatus").asText()).isEqualTo("COMPLETE");
        assertThat(dashboard.get("rows").get(0).get("workedMinutes").asInt()).isEqualTo(540);
    }

    @Test
    void aClockOutWithoutAClockInIs409AndADuplicateIs409() throws Exception {
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                body(employeeId, "CLOCK_OUT", today(), "17:00")).andExpect(status().isConflict())).get("code").asText())
                .isEqualTo("CLOCK_IN_REQUIRED");
        admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", today(), "08:05"))
                .andExpect(status().isCreated());
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                body(employeeId, "CLOCK_IN", today(), "08:10")).andExpect(status().isConflict())).get("code").asText())
                .isEqualTo("ENTRY_ALREADY_EXISTS");
    }

    @Test
    void lateMinutesAreComputedForAClockIn() throws Exception {
        JsonNode created = admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                body(employeeId, "CLOCK_IN", today(), "08:30")).andExpect(status().isCreated()));
        assertThat(created.get("lateMinutes").asInt()).isEqualTo(30);
    }

    @Test
    void aFutureTimeOrAFutureWorkDateIsRejected() throws Exception {
        for (Map<String, Object> bad : java.util.List.of(
                body(employeeId, "CLOCK_IN", today(), "23:00"),
                body(employeeId, "CLOCK_IN", today().plusDays(1), "08:00"))) {
            assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries", bad)
                    .andExpect(status().isBadRequest())).get("code").asText()).isEqualTo("INVALID_ENTRY_TIME");
        }
    }

    @Test
    void anEmployeeWithoutABranchAndAnotherTenantsEmployeeAreRefused() throws Exception {
        long noBranch = data.employee(tenant.companyId(), null, "ADMIN");
        assertThat(admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                body(noBranch, "CLOCK_IN", today(), "08:00")).andExpect(status().isConflict())).get("code").asText())
                .isEqualTo("NO_BRANCH_ASSIGNED");

        TestData.Tenant other = data.tenant();
        long foreign = admins.employee(other);
        admins.post(admin.token(), "/api/admin/time-entries", body(foreign, "CLOCK_IN", today(), "08:00"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInactiveEmployeeMayReceiveAHistoricalFix() throws Exception {
        jdbc.update("update employee set active = false where id = ?", employeeId);
        admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", today(), "08:00"))
                .andExpect(status().isCreated());
    }

    @Test
    void todaysAdminClockInLetsTheEmployeeClockOutFromThePhone() throws Exception {
        // The employee's camera is broken: the admin records the clock-in, the phone does the clock-out.
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
        TestData.TestUser user = new TestData.TestUser(employeeId, code, TestData.PIN);
        CheckInClient.Actor actor = new CheckInClient.Actor(tenant, user, data.login(tenant, user));
        admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", today(), "08:00"))
                .andExpect(status().isCreated());

        JsonNode state = client.json(client.get(actor, "/api/time-entries/today").andExpect(status().isOk()));
        assertThat(state.get("nextAction").asText()).isEqualTo("CLOCK_OUT");
        client.post(actor, client.form("CLOCK_OUT", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated());
    }

    @Test
    void aPastDatedAdminClockInDoesNotBlockTheEmployeesClockInToday() throws Exception {
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
        TestData.TestUser user = new TestData.TestUser(employeeId, code, TestData.PIN);
        CheckInClient.Actor actor = new CheckInClient.Actor(tenant, user, data.login(tenant, user));
        LocalDate yesterday = today().minusDays(1);
        // Created now (receipt time = now) for yesterday, with no clock-out.
        admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", yesterday, "08:00"))
                .andExpect(status().isCreated());

        JsonNode state = client.json(client.get(actor, "/api/time-entries/today").andExpect(status().isOk()));
        assertThat(state.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(state.get("missingClockOut").asBoolean()).isTrue();
        client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated());
    }

    @Test
    void creatingAClockInWhileTheEmployeeClocksInYieldsExactlyOneRow() throws Exception {
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
        TestData.TestUser user = new TestData.TestUser(employeeId, code, TestData.PIN);
        CheckInClient.Actor actor = new CheckInClient.Actor(tenant, user, data.login(tenant, user));
        byte[] photo = TestImages.jpeg();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.List<Callable<Integer>> calls = java.util.List.of(
                    () -> admins.post(admin.token(), "/api/admin/time-entries",
                            body(employeeId, "CLOCK_IN", today(), "08:00")).andReturn().getResponse().getStatus(),
                    () -> client.post(actor, client.form("CLOCK_IN", clock.instant()), photo).andReturn()
                            .getResponse().getStatus());
            java.util.List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(client.entryCount(employeeId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anEmployeeTokenCannotCreateEntries() throws Exception {
        String code = jdbc.queryForObject("select code from employee where id = ?", String.class, employeeId);
        String token = admins.tokenFor(tenant, new TestData.TestUser(employeeId, code, TestData.PIN));
        admins.post(token, "/api/admin/time-entries", body(employeeId, "CLOCK_IN", today(), "08:00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theWorkDateMustBeWithinFiveYearsBackAndNotInTheFuture() throws Exception {
        LocalDate limit = today().minusYears(5);
        admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", limit, "08:00"))
                .andExpect(status().isCreated());
        for (LocalDate bad : java.util.List.of(LocalDate.of(1900, 1, 1), limit.minusDays(1), today().plusDays(1))) {
            admins.post(admin.token(), "/api/admin/time-entries", body(employeeId, "CLOCK_IN", bad, "08:00"))
                    .andExpect(status().isBadRequest());
        }
    }
}
