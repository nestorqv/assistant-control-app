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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every admin route used with the id of another company's entry or employee answers 404 (never 403, never the
 * data) and leaves no adjustment and no audit row behind.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
class AdminTenantIsolationIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");
    private static final String REASON = "Verificado con la foto";

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

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    @Test
    void everyEntryRouteIs404ForAnotherCompanysAdminAndChangesNothing() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated())).get("id").asLong();
        AdminClient.Admin foreign = admins.admin(data.tenant());
        String base = "/api/admin/time-entries/" + id;
        String statusBefore = jdbc.queryForObject("select status from time_entry where id = ?", String.class, id);
        String effective = clock.instant().toString();

        admins.get(foreign.token(), base).andExpect(status().isNotFound());
        admins.get(foreign.token(), base + "/photo").andExpect(status().isNotFound());
        for (String verb : new String[] {"approve", "reject"}) {
            admins.post(foreign.token(), base + "/" + verb, Map.of("reason", REASON, "expectedStatus", statusBefore))
                    .andExpect(status().isNotFound());
        }
        admins.post(foreign.token(), base + "/correct", Map.of("reason", REASON, "expectedStatus", statusBefore,
                "expectedEffectiveTime", effective, "localTime", today() + "T07:30")).andExpect(status().isNotFound());

        assertThat(admins.adjustmentCount(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where target_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select status from time_entry where id = ?", String.class, id))
                .isEqualTo(statusBefore);
    }

    @Test
    void theOwnerStillSeesTheEntryAfterTheForeignAttempts() throws Exception {
        CheckInClient.Actor actor = client.actor();
        long id = client.json(client.post(actor, client.form("CLOCK_IN", clock.instant()), TestImages.jpeg())
                .andExpect(status().isCreated())).get("id").asLong();
        AdminClient.Admin owner = admins.admin(actor.tenant());
        admins.get(owner.token(), "/api/admin/time-entries/" + id).andExpect(status().isOk());
        admins.get(owner.token(), "/api/admin/time-entries/" + id + "/photo").andExpect(status().isOk());
    }

    @Test
    void creatingAnEntryForAnotherCompanysEmployeeIs404AndWritesNothing() throws Exception {
        TestData.Tenant mine = data.tenant();
        AdminClient.Admin admin = admins.admin(mine);
        TestData.Tenant other = data.tenant();
        long foreignEmployee = admins.employee(other);

        admins.post(admin.token(), "/api/admin/time-entries", Map.of("employeeId", foreignEmployee, "type", "CLOCK_IN",
                "workDate", today().toString(), "localTime", today() + "T07:00", "reason", REASON))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from time_entry where employee_id = ?", Integer.class,
                foreignEmployee)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from adjustment where company_id in (?, ?)", Integer.class,
                mine.companyId(), other.companyId())).isZero();
    }

    @Test
    void filteringTheQueueByAnotherCompanysEmployeeListsNothing() throws Exception {
        TestData.Tenant mine = data.tenant();
        AdminClient.Admin admin = admins.admin(mine);
        long mineEmployee = admins.employee(mine);
        admins.entry(mine, mineEmployee, "CLOCK_IN", today(), clock.instant(), "PENDING_REVIEW", "NO_GPS", 0);
        TestData.Tenant other = data.tenant();
        long foreignEmployee = admins.employee(other);
        admins.entry(other, foreignEmployee, "CLOCK_IN", today(), clock.instant(), "PENDING_REVIEW", "NO_GPS", 0);

        JsonNode filtered = admins.json(admins.get(admin.token(),
                "/api/admin/review-queue?employeeId=" + foreignEmployee).andExpect(status().isOk()));
        assertThat(filtered.get("totalItems").asInt()).isZero();
        assertThat(filtered.get("items")).isEmpty();

        JsonNode all = admins.json(admins.get(admin.token(), "/api/admin/review-queue").andExpect(status().isOk()));
        assertThat(all.get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    void anotherCompanysBranchOnTheDashboardIs404() throws Exception {
        AdminClient.Admin admin = admins.admin(data.tenant());
        TestData.Tenant other = data.tenant();
        admins.get(admin.token(), "/api/admin/dashboard?branchId=" + other.branchId()).andExpect(status().isNotFound());
    }
}
