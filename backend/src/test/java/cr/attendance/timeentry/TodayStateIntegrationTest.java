package cr.attendance.timeentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.CheckInClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import cr.attendance.support.TestImages;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class TodayStateIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

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

    private JsonNode today(CheckInClient.Actor actor) throws Exception {
        return client.json(client.get(actor, "/api/time-entries/today").andExpect(status().isOk()));
    }

    @Test
    void withoutConsentTheButtonIsBlocked() throws Exception {
        CheckInClient.Actor actor = client.actor(data.tenant(), "EMPLOYEE", false);
        JsonNode body = today(actor);
        assertThat(body.get("blockedReason").asText()).isEqualTo("CONSENT_REQUIRED");
        assertThat(body.get("nextAction").asText()).isEqualTo("NONE");
        assertThat(body.get("consentPending").asBoolean()).isTrue();
    }

    @Test
    void anAdminWithoutABranchIsBlocked() throws Exception {
        CheckInClient.Actor admin = client.actor(data.tenant(), "ADMIN", true);
        JsonNode body = today(admin);
        assertThat(body.get("blockedReason").asText()).isEqualTo("NO_BRANCH_ASSIGNED");
        assertThat(body.get("nextAction").asText()).isEqualTo("NONE");
        assertThat(body.get("branch").isNull()).isTrue();
    }

    @Test
    void aReadyEmployeeSeesTheScheduleOfTheirWorkDateAndTheBranchWithoutCoordinates() throws Exception {
        CheckInClient.Actor actor = client.actor();
        int isoDay = clock.instant().atZone(CR).getDayOfWeek().getValue();
        data.schedule(actor.companyId(), actor.employeeId(), isoDay, LocalTime.of(8, 0), LocalTime.of(17, 0), 5);
        data.schedule(actor.companyId(), actor.employeeId(), isoDay % 7 + 1, LocalTime.of(10, 0), LocalTime.of(18, 0), 0);

        JsonNode body = today(actor);
        assertThat(body.get("blockedReason").isNull()).isTrue();
        assertThat(body.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(body.get("schedule").get("startTime").asText()).startsWith("08:00");
        assertThat(body.get("schedule").get("graceMinutes").asInt()).isEqualTo(5);
        assertThat(body.get("schedule").get("overnight").asBoolean()).isFalse();
        assertThat(body.get("branch").get("radiusMeters").asInt()).isEqualTo(100);
        assertThat(body.get("branch").has("latitude")).isFalse();
        assertThat(body.get("branch").has("longitude")).isFalse();
        assertThat(body.get("timeZone").asText()).isEqualTo("America/Costa_Rica");
        assertThat(Instant.parse(body.get("serverTime").asText())).isEqualTo(clock.instant());
        assertThat(body.get("clockIn").isNull()).isTrue();
        assertThat(body.get("workedMinutes").isNull()).isTrue();
    }

    @Test
    void anotherCompaniesEntriesNeverAppearEvenWithTheSameEmployeeCode() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long employeeA = jdbc.queryForObject("""
                insert into employee (company_id, branch_id, code, name, pin_hash, role)
                values (?, ?, 'SAME01', 'A', ?, 'EMPLOYEE') returning id
                """, Long.class, a.companyId(), a.branchId(), data.pinHash());
        long employeeB = jdbc.queryForObject("""
                insert into employee (company_id, branch_id, code, name, pin_hash, role)
                values (?, ?, 'SAME01', 'B', ?, 'EMPLOYEE') returning id
                """, Long.class, b.companyId(), b.branchId(), data.pinHash());
        data.consent(employeeA);
        data.consent(employeeB);
        CheckInClient.Actor actorA = new CheckInClient.Actor(a, new TestData.TestUser(employeeA, "SAME01", TestData.PIN),
                data.login(a, new TestData.TestUser(employeeA, "SAME01", TestData.PIN)));
        CheckInClient.Actor actorB = new CheckInClient.Actor(b, new TestData.TestUser(employeeB, "SAME01", TestData.PIN),
                data.login(b, new TestData.TestUser(employeeB, "SAME01", TestData.PIN)));

        client.post(actorB, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg()).andExpect(status().isCreated());

        JsonNode forA = today(actorA);
        assertThat(forA.get("nextAction").asText()).isEqualTo("CLOCK_IN");
        assertThat(forA.get("clockIn").isNull()).isTrue();
        assertThat(today(actorB).get("nextAction").asText()).isEqualTo("CLOCK_OUT");
    }
}
