package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Weekly schedule: replace the whole week with an expected-week check. */
@IntegrationTest
class AdminScheduleIntegrationTest {

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    private static Map<String, Object> day(int dayOfWeek, String start, String end, int grace) {
        return Map.of("dayOfWeek", dayOfWeek, "startTime", start, "endTime", end, "graceMinutes", grace);
    }

    private static Map<String, Object> week(List<Map<String, Object>> expected, List<Map<String, Object>> days) {
        return Map.of("expected", expected, "days", days);
    }

    private int audits(long companyId) {
        return jdbc.queryForObject("select count(*) from audit_log where company_id = ? and action = 'SCHEDULE_UPDATE'",
                Integer.class, companyId);
    }

    @Test
    void getReturnsTheSortedWeekAsHhMmAndAnEmptyWeekForNewEmployees() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        admins.get(admin.token(), "/api/admin/employees/" + employee + "/schedule").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        data.schedule(t.companyId(), employee, 3, java.time.LocalTime.of(9, 0), java.time.LocalTime.of(18, 0), 10);
        data.schedule(t.companyId(), employee, 1, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(17, 0), 5);
        JsonNode week = admins.json(admins.get(admin.token(), "/api/admin/employees/" + employee + "/schedule").andExpect(status().isOk()));
        assertThat(week.get(0).get("dayOfWeek").asInt()).isEqualTo(1);
        assertThat(week.get(0).get("startTime").asText()).isEqualTo("08:00");
        assertThat(week.get(1).get("endTime").asText()).isEqualTo("18:00");
    }

    @Test
    void replaceAddsChangesAndRemovesDaysInPlaceIncludingTheSameDayWithNewTimes() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String path = "/api/admin/employees/" + employee + "/schedule";

        // Empty week -> Monday 08:00-17:00 and Tuesday 22:00-06:00 (overnight is allowed).
        List<Map<String, Object>> first = List.of(day(1, "08:00", "17:00", 5), day(2, "22:00", "06:00", 0));
        admins.put(admin.token(), path, week(List.of(), first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(audits(t.companyId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select details from audit_log where company_id = ? and action = 'SCHEDULE_UPDATE'",
                String.class, t.companyId())).isEqualTo("days=1,2");
        assertThat(jdbc.queryForObject("select target_type from audit_log where company_id = ? and action = 'SCHEDULE_UPDATE'",
                String.class, t.companyId())).isEqualTo("EMPLOYEE");

        // Same Monday with other times (a delete + insert would hit the unique key), drop Tuesday, add Saturday.
        List<Map<String, Object>> second = List.of(day(1, "09:00", "18:00", 5), day(6, "08:00", "12:00", 15));
        JsonNode result = admins.json(admins.put(admin.token(), path, week(first, second)).andExpect(status().isOk()));
        assertThat(result.size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where employee_id = ?", Integer.class, employee)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select to_char(start_time, 'HH24:MI') from work_schedule where employee_id = ? and day_of_week = 1",
                String.class, employee)).isEqualTo("09:00");

        // An empty week removes everything.
        admins.put(admin.token(), path, week(second, List.of())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where employee_id = ?", Integer.class, employee)).isZero();
    }

    @Test
    void anIdenticalWeekIsANoOpWithoutAnAuditRowAndAStaleExpectedWeekIs409() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String path = "/api/admin/employees/" + employee + "/schedule";
        List<Map<String, Object>> monday = List.of(day(1, "08:00", "17:00", 5));
        admins.put(admin.token(), path, week(List.of(), monday)).andExpect(status().isOk());
        assertThat(audits(t.companyId())).isEqualTo(1);
        admins.put(admin.token(), path, week(monday, monday)).andExpect(status().isOk());
        assertThat(audits(t.companyId())).isEqualTo(1);

        // Someone else changed the week after this editor loaded the empty one.
        admins.put(admin.token(), path, week(List.of(), List.of(day(2, "08:00", "17:00", 5))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where employee_id = ?", Integer.class, employee)).isEqualTo(1);
    }

    @Test
    void anExpectedWeekWithTheSameDayButOtherTimesIs409AndNothingChanges() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String path = "/api/admin/employees/" + employee + "/schedule";
        admins.put(admin.token(), path, week(List.of(), List.of(day(1, "09:00", "17:00", 5)))).andExpect(status().isOk());
        int audits = audits(t.companyId());
        // The editor loaded Monday 08:00, but the stored Monday starts at 09:00.
        admins.put(admin.token(), path, week(List.of(day(1, "08:00", "17:00", 5)), List.of(day(1, "10:00", "18:00", 5))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_CHANGED"));
        assertThat(jdbc.queryForObject("select to_char(start_time, 'HH24:MI') from work_schedule where employee_id = ? and day_of_week = 1",
                String.class, employee)).isEqualTo("09:00");
        assertThat(audits(t.companyId())).isEqualTo(audits);
    }

    @Test
    void invalidWeeksGive400() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String path = "/api/admin/employees/" + employee + "/schedule";
        List<List<Map<String, Object>>> bad = new ArrayList<>();
        bad.add(List.of(day(0, "08:00", "17:00", 5)));
        bad.add(List.of(day(8, "08:00", "17:00", 5)));
        bad.add(List.of(day(1, "08:00", "17:00", 5), day(1, "09:00", "18:00", 5)));
        bad.add(List.of(day(1, "08:00", "08:00", 5)));
        bad.add(List.of(day(1, "08:00", "17:00", -1)));
        bad.add(List.of(day(1, "08:00", "17:00", 241)));
        bad.add(List.of(day(1, "8:00", "17:00", 5)));
        bad.add(List.of(day(1, "08:00", "24:00", 5)));
        List<Map<String, Object>> eight = new ArrayList<>();
        for (int d = 1; d <= 8; d++) {
            eight.add(day(Math.min(d, 7), "08:00", "17:00", 5));
        }
        bad.add(eight);
        for (List<Map<String, Object>> days : bad) {
            admins.put(admin.token(), path, week(List.of(), days)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where employee_id = ?", Integer.class, employee)).isZero();
    }

    @Test
    void lateMinutesOfStoredEntriesNeverChangeAndOtherTenantsAre404() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        long entry = admins.entry(t, employee, "CLOCK_IN", java.time.LocalDate.now(), java.time.Instant.now(), "VALID", null, 7);
        List<Map<String, Object>> monday = List.of(day(1, "05:00", "06:00", 240));
        admins.put(admin.token(), "/api/admin/employees/" + employee + "/schedule", week(List.of(), monday)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select late_minutes from time_entry where id = ?", Integer.class, entry)).isEqualTo(7);

        TestData.Tenant other = data.tenant();
        long foreign = data.employee(other.companyId(), other.branchId(), "EMPLOYEE");
        admins.put(admin.token(), "/api/admin/employees/" + foreign + "/schedule", week(List.of(), monday)).andExpect(status().isNotFound());
    }

    @Test
    void timesAreComparedAndReturnedAsHhMmWhileStoredSecondsAreIgnoredAndHhMmSsIsRefused() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        long employee = data.employee(t.companyId(), t.branchId(), "EMPLOYEE");
        String path = "/api/admin/employees/" + employee + "/schedule";
        // The column keeps seconds; the API speaks minutes.
        jdbc.update("insert into work_schedule (company_id, employee_id, day_of_week, start_time, end_time, grace_minutes) "
                + "values (?, ?, 1, '08:00:30', '17:00:45', 5)", t.companyId(), employee);
        List<Map<String, Object>> asShown = List.of(day(1, "08:00", "17:00", 5));
        JsonNode shown = admins.json(admins.get(admin.token(), path).andExpect(status().isOk()));
        assertThat(shown.get(0).get("startTime").asText()).isEqualTo("08:00");
        assertThat(shown.get(0).get("endTime").asText()).isEqualTo("17:00");

        // What the editor loaded (HH:mm) is accepted as the expected week, and an unchanged week is a no-op.
        admins.put(admin.token(), path, week(asShown, asShown)).andExpect(status().isOk());
        assertThat(audits(t.companyId())).isZero();
        assertThat(jdbc.queryForObject("select to_char(start_time, 'HH24:MI:SS') from work_schedule where employee_id = ?",
                String.class, employee)).isEqualTo("08:00:30");

        // Seconds are not part of the contract, in expected or in days.
        admins.put(admin.token(), path, week(List.of(day(1, "08:00:00", "17:00:00", 5)), asShown)).andExpect(status().isBadRequest());
        admins.put(admin.token(), path, week(asShown, List.of(day(1, "08:00:00", "17:00", 5)))).andExpect(status().isBadRequest());
        assertThat(audits(t.companyId())).isZero();
    }
}
