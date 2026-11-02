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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class AdminDashboardIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

    @Autowired
    private AdminClient admins;

    @Autowired
    private MutableClock clock;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void nineInTheMorning() {
        clock.advanceToNext(CR, LocalTime.of(9, 0));
    }

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    private Instant local(String time) {
        return today().atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }

    private long person(TestData.Tenant t, String name, String start, String end) {
        long id = admins.employee(t);
        jdbc.update("update employee set name = ? where id = ?", name, id);
        data.schedule(t.companyId(), id, today().getDayOfWeek().getValue(), LocalTime.parse(start), LocalTime.parse(end), 5);
        return id;
    }

    /** Page 2 of the blueprint: 12 people, 9 present, 2 late, 3 not clocked, 1 outside the area. */
    private Map<String, Long> mockup(TestData.Tenant t) {
        Map<String, Long> ids = new java.util.LinkedHashMap<>();
        String[][] people = {
            {"Maria", "08:00", "17:00"}, {"Jose", "08:00", "17:00"}, {"Ana", "08:00", "17:00"},
            {"Luis", "08:00", "17:00"}, {"Karla", "06:00", "14:00"}, {"Diego", "08:00", "17:00"},
            {"Marco", "08:00", "17:00"}, {"Sofia", "08:00", "17:00"}, {"Andres", "08:00", "17:00"},
            {"Lucia", "08:00", "17:00"}, {"Pablo", "08:00", "17:00"}, {"Elena", "08:00", "17:00"},
        };
        for (String[] p : people) {
            ids.put(p[0], person(t, p[0], p[1], p[2]));
        }
        LocalDate d = today();
        admins.entry(t, ids.get("Maria"), "CLOCK_IN", d, local("07:58"), "VALID", null, 0);
        admins.entry(t, ids.get("Jose"), "CLOCK_IN", d, local("08:17"), "VALID", null, 17);
        admins.entry(t, ids.get("Ana"), "CLOCK_IN", d, local("07:52"), "VALID", null, 0);
        admins.entry(t, ids.get("Luis"), "CLOCK_IN", d, local("08:01"), "PENDING_REVIEW", "OUTSIDE_GEOFENCE", 0);
        admins.entry(t, ids.get("Karla"), "CLOCK_IN", d, local("06:00"), "VALID", null, 0);
        admins.entry(t, ids.get("Karla"), "CLOCK_OUT", d, local("08:44"), "VALID", null, null);
        admins.entry(t, ids.get("Marco"), "CLOCK_IN", d, local("07:55"), "VALID", null, 0);
        admins.entry(t, ids.get("Sofia"), "CLOCK_IN", d, local("08:03"), "VALID", null, 0);
        admins.entry(t, ids.get("Andres"), "CLOCK_IN", d, local("08:12"), "VALID", null, 12);
        admins.entry(t, ids.get("Lucia"), "CLOCK_IN", d, local("07:59"), "VALID", null, 0);
        return ids;
    }

    private Map<String, String> statuses(JsonNode body) {
        return java.util.stream.StreamSupport.stream(body.get("rows").spliterator(), false)
                .collect(Collectors.toMap(r -> r.get("employee").get("name").asText(), r -> r.get("dayStatus").asText()));
    }

    @Test
    void theBlueprintFixtureGivesTheDocumentedKpisAndStatuses() throws Exception {
        TestData.Tenant t = data.tenant();
        mockup(t);
        AdminClient.Admin admin = admins.admin(t);

        JsonNode body = admins.json(admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()));

        JsonNode kpis = body.get("kpis");
        assertThat(kpis.get("present").asInt()).isEqualTo(9);
        assertThat(kpis.get("expected").asInt()).isEqualTo(12);
        assertThat(kpis.get("late").asInt()).isEqualTo(2);
        assertThat(kpis.get("notClocked").asInt()).isEqualTo(3);
        assertThat(kpis.get("notYetDue").asInt()).isZero();
        assertThat(kpis.get("toReview").asInt()).isEqualTo(1);
        assertThat(kpis.get("toReviewByReason").get("OUTSIDE_GEOFENCE").asInt()).isEqualTo(1);
        assertThat(body.get("rows")).hasSize(12);
        assertThat(body.get("timeZone").asText()).isEqualTo("America/Costa_Rica");
        assertThat(body.get("today").asText()).isEqualTo(today().toString());

        Map<String, String> status = statuses(body);
        assertThat(status).containsEntry("Maria", "ON_TIME").containsEntry("Jose", "LATE")
                .containsEntry("Luis", "OUTSIDE_AREA").containsEntry("Karla", "COMPLETE")
                .containsEntry("Diego", "NOT_CLOCKED").containsEntry("Andres", "LATE");
        JsonNode karla = row(body, "Karla");
        assertThat(karla.get("workedMinutes").asInt()).isEqualTo(164);
        assertThat(karla.get("inProgress").asBoolean()).isFalse();
        assertThat(row(body, "Maria").get("inProgress").asBoolean()).isTrue();
        assertThat(row(body, "Maria").get("workedMinutes").isNull()).isTrue();
    }

    private JsonNode row(JsonNode body, String name) {
        for (JsonNode r : body.get("rows")) {
            if (name.equals(r.get("employee").get("name").asText())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + name);
    }

    @Test
    void rowsCarryNoCoordinatesAndNoClientUuid() throws Exception {
        TestData.Tenant t = data.tenant();
        mockup(t);
        AdminClient.Admin admin = admins.admin(t);
        String raw = admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(raw).doesNotContain("latitude").doesNotContain("longitude").doesNotContain("clientUuid")
                .doesNotContain("storageKey");
    }

    @Test
    void theDefaultDateIsTheCompanyLocalDayEvenWhenUtcIsAlreadyTomorrow() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        // 23:30 in Costa Rica is 05:30 UTC of the next day.
        Instant at = clock.advanceToNext(CR, LocalTime.of(23, 30));
        LocalDate crDate = at.atZone(CR).toLocalDate();
        assertThat(at.atZone(ZoneId.of("UTC")).toLocalDate()).isEqualTo(crDate.plusDays(1));

        JsonNode body = admins.json(admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()));
        assertThat(body.get("date").asText()).isEqualTo(crDate.toString());
        assertThat(body.get("today").asText()).isEqualTo(crDate.toString());
    }

    @Test
    void theBranchFilterUsesTheEntryBranchAndAnEmployeeMovedSinceKeepsTheirRow() throws Exception {
        TestData.Tenant t = data.tenant();
        long other = data.branchAt(t.companyId(), 10.0, -84.0, 100);
        person(t, "Stays", "08:00", "17:00");
        long moved = person(t, "Moved", "08:00", "17:00");
        // "Moved" clocked in at the test branch, and has been assigned to the other branch since.
        admins.entry(t, moved, "CLOCK_IN", today(), local("07:58"), "VALID", null, 0);
        jdbc.update("update employee set branch_id = ? where id = ?", other, moved);
        AdminClient.Admin admin = admins.admin(t);

        JsonNode atTestBranch = admins.json(admins.get(admin.token(),
                "/api/admin/dashboard?branchId=" + t.branchId()).andExpect(status().isOk()));
        assertThat(statuses(atTestBranch).keySet()).containsExactlyInAnyOrder("Stays", "Moved");
        JsonNode atOther = admins.json(admins.get(admin.token(), "/api/admin/dashboard?branchId=" + other)
                .andExpect(status().isOk()));
        assertThat(statuses(atOther)).isEmpty();
    }

    @Test
    void anInactiveEmployeeWithAnEntryIsListedAndOneWithoutIsNot() throws Exception {
        TestData.Tenant t = data.tenant();
        long worked = person(t, "Worked", "08:00", "17:00");
        long idle = person(t, "Idle", "08:00", "17:00");
        admins.entry(t, worked, "CLOCK_IN", today(), local("07:58"), "VALID", null, 0);
        jdbc.update("update employee set active = false where id in (?, ?)", worked, idle);
        AdminClient.Admin admin = admins.admin(t);

        JsonNode body = admins.json(admins.get(admin.token(), "/api/admin/dashboard").andExpect(status().isOk()));
        assertThat(statuses(body).keySet()).containsExactly("Worked");
        assertThat(row(body, "Worked").get("employee").get("active").asBoolean()).isFalse();
    }

    @Test
    void anotherTenantIsNeverVisibleAndItsBranchIs404() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        person(b, "Foreign", "08:00", "17:00");
        AdminClient.Admin adminA = admins.admin(a);

        JsonNode body = admins.json(admins.get(adminA.token(), "/api/admin/dashboard").andExpect(status().isOk()));
        assertThat(body.get("rows")).isEmpty();
        admins.get(adminA.token(), "/api/admin/dashboard?branchId=" + b.branchId()).andExpect(status().isNotFound());
    }

    @Test
    void aFutureDateIsAllowedAndEveryScheduledRowIsNotYetDue() throws Exception {
        TestData.Tenant t = data.tenant();
        person(t, "Planned", "08:00", "17:00");
        AdminClient.Admin admin = admins.admin(t);
        // Same weekday next week, so the schedule applies.
        LocalDate next = today().plusDays(7);
        JsonNode body = admins.json(admins.get(admin.token(), "/api/admin/dashboard?date=" + next)
                .andExpect(status().isOk()));
        assertThat(statuses(body)).containsEntry("Planned", "NOT_YET_DUE");
        assertThat(body.get("kpis").get("notYetDue").asInt()).isEqualTo(1);
    }

    @Test
    void anEmployeeTokenIsForbiddenWithAnEmptyBody() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser employee = data.user(t, "EMPLOYEE");
        String token = admins.tokenFor(t, employee);
        String content = admins.get(token, "/api/admin/dashboard").andExpect(status().isForbidden()).andReturn()
                .getResponse().getContentAsString();
        assertThat(content).isEmpty();
        admins.get(null, "/api/admin/dashboard").andExpect(status().isUnauthorized());
    }

    @Test
    void malformedAndOutOfRangeDatesAreRejectedWithoutEchoingTheInput() throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        for (String bad : List.of("2026-13-01", "not-a-date", "1999-01-01", "2099-01-01")) {
            String content = admins.get(admin.token(), "/api/admin/dashboard?date=" + bad)
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(content).contains("INVALID_REQUEST").doesNotContain(bad);
        }
    }

    @Test
    void inProgressFollowsTheMaximumShiftNotTheCalendarDate() throws Exception {
        TestData.Tenant t = data.tenant();
        long overnight = person(t, "Overnight", "22:00", "06:00");
        long old = person(t, "Forgotten", "08:00", "17:00");
        LocalDate yesterday = today().minusDays(1);
        // 22:00 yesterday, viewed at 09:00 today: 11 hours, still in progress.
        admins.entry(t, overnight, "CLOCK_IN", yesterday, at(yesterday, "22:00"), "VALID", null, 0);
        // A pending clock-in from 20 hours ago on a past date: not "en turno", the clock-out is missing.
        LocalDate older = today().minusDays(2);
        admins.entry(t, old, "CLOCK_IN", older, at(older, "08:00"), "PENDING_REVIEW", "SYNCED_LATE", 0);
        AdminClient.Admin admin = admins.admin(t);

        JsonNode y = admins.json(admins.get(admin.token(), "/api/admin/dashboard?date=" + yesterday)
                .andExpect(status().isOk()));
        assertThat(row(y, "Overnight").get("inProgress").asBoolean()).isTrue();
        assertThat(row(y, "Overnight").get("dayStatus").asText()).isEqualTo("ON_TIME");

        JsonNode o = admins.json(admins.get(admin.token(), "/api/admin/dashboard?date=" + older)
                .andExpect(status().isOk()));
        assertThat(row(o, "Forgotten").get("inProgress").asBoolean()).isFalse();
        assertThat(row(o, "Forgotten").get("dayStatus").asText()).isEqualTo("NEEDS_REVIEW");
    }

    private Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }
}
