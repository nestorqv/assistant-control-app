package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lock order of the review actions (employee row first, then the entry): racing admins never produce a day
 * whose clock-out is not after its clock-in, and one correction is applied once.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
class ReviewConcurrencyIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final int ROUNDS = 6;

    @Autowired
    private AdminClient admins;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void eightInTheEvening() {
        clock.advanceToNext(CR, LocalTime.of(20, 0));
    }

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    private Instant local(String time) {
        return today().atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }

    private List<Integer> runTogether(List<Callable<Integer>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoAdminsCorrectingTheSameEntryApplyExactlyOneCorrection() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestData.Tenant tenant = data.tenant();
            AdminClient.Admin first = admins.admin(tenant);
            AdminClient.Admin second = admins.admin(tenant);
            long employee = admins.employee(tenant);
            data.schedule(tenant.companyId(), employee, today().getDayOfWeek().getValue(), LocalTime.of(8, 0),
                    LocalTime.of(17, 0), 5);
            long id = admins.entry(tenant, employee, "CLOCK_IN", today(), local("08:17"), "PENDING_REVIEW",
                    "SYNCED_LATE", 17);
            String expected = admins.json(admins.get(first.token(), "/api/admin/time-entries/" + id))
                    .get("effectiveTime").asText();
            String path = "/api/admin/time-entries/" + id + "/correct";

            List<Integer> statuses = runTogether(List.of(
                    () -> admins.post(first.token(), path, correction(expected, "08:03", "PENDING_REVIEW")).andReturn().getResponse()
                            .getStatus(),
                    () -> admins.post(second.token(), path, correction(expected, "08:05", "PENDING_REVIEW")).andReturn().getResponse()
                            .getStatus()));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject(
                    "select count(*) from adjustment where time_entry_id = ? and field = 'effective_time'",
                    Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from time_entry where id = ?", String.class, id))
                    .isEqualTo("VALID");
        }
    }

    @Test
    void movingTheClockInPastANewClockOutAndRecordingThatClockOutNeverBothSucceed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            TestData.Tenant tenant = data.tenant();
            AdminClient.Admin corrector = admins.admin(tenant);
            AdminClient.Admin creator = admins.admin(tenant);
            long employee = admins.employee(tenant);
            long in = admins.entry(tenant, employee, "CLOCK_IN", today(), local("08:00"), "VALID", null, 0);
            String expected = admins.json(admins.get(corrector.token(), "/api/admin/time-entries/" + in))
                    .get("effectiveTime").asText();

            List<Integer> statuses = runTogether(List.of(
                    () -> admins.post(corrector.token(), "/api/admin/time-entries/" + in + "/correct",
                            correction(expected, "17:30", "VALID")).andReturn().getResponse().getStatus(),
                    () -> admins.post(creator.token(), "/api/admin/time-entries",
                            Map.of("employeeId", employee, "type", "CLOCK_OUT", "workDate", today().toString(),
                                    "localTime", today() + "T17:00", "reason", "Olvidó marcar la salida"))
                            .andReturn().getResponse().getStatus()));

            // Whatever the order, the second action sees the first one: exactly one wins, the other is invalid.
            assertThat(statuses.stream().filter(s -> s == 200 || s == 201).count()).isEqualTo(1);
            assertThat(statuses).contains(400);

            Instant clockIn = jdbc.queryForObject(
                    "select effective_time from time_entry where employee_id = ? and type = 'CLOCK_IN'",
                    java.sql.Timestamp.class, employee).toInstant();
            List<java.sql.Timestamp> outs = jdbc.queryForList(
                    "select effective_time from time_entry where employee_id = ? and type = 'CLOCK_OUT'",
                    java.sql.Timestamp.class, employee);
            assertThat(outs.stream().allMatch(out -> out.toInstant().isAfter(clockIn))).isTrue();
        }
    }

    @Test
    void anApprovalAndACorrectionRacingOnTheSameEntryLeaveAConsistentHistory() throws Exception {
        TestData.Tenant tenant = data.tenant();
        AdminClient.Admin approver = admins.admin(tenant);
        AdminClient.Admin corrector = admins.admin(tenant);
        long employee = admins.employee(tenant);
        long id = admins.entry(tenant, employee, "CLOCK_IN", today(), local("08:17"), "PENDING_REVIEW",
                "SYNCED_LATE", 17);
        String expected = admins.json(admins.get(approver.token(), "/api/admin/time-entries/" + id))
                .get("effectiveTime").asText();
        String base = "/api/admin/time-entries/" + id;

        List<Integer> statuses = runTogether(List.of(
                () -> admins.post(approver.token(), base + "/approve",
                        Map.of("reason", "Verificado con la foto", "expectedStatus", "PENDING_REVIEW")).andReturn()
                        .getResponse().getStatus(),
                () -> admins.post(corrector.token(), base + "/correct", correction(expected, "08:03", "PENDING_REVIEW")).andReturn()
                        .getResponse().getStatus()));

        // Both read PENDING_REVIEW; the second to get the lock finds VALID and answers 409.
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        JsonNode detail = admins.json(admins.get(approver.token(), base));
        assertThat(detail.get("status").asText()).isEqualTo("VALID");
        int rows = admins.adjustmentCount(id);
        assertThat(rows).isBetween(1, 3);
        assertThat(jdbc.queryForObject("select count(*) from adjustment where time_entry_id = ? and field = 'status'",
                Integer.class, id)).isEqualTo(1);
    }

    private Map<String, Object> correction(String expectedEffective, String time, String status) {
        return Map.of("reason", "Hora del teléfono incorrecta", "expectedStatus", status,
                "expectedEffectiveTime", expectedEffective, "localTime", today() + "T" + time);
    }
}
