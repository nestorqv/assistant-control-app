package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(MutableClockConfig.class)
class ReviewQueueIntegrationTest {

    private static final ZoneId CR = ZoneId.of("America/Costa_Rica");

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

    @BeforeEach
    void setUp() throws Exception {
        clock.advanceToNext(CR, LocalTime.of(20, 0));
        tenant = data.tenant();
        admin = admins.admin(tenant);
    }

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    private Instant at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(CR).toInstant();
    }

    private JsonNode queue(String query) throws Exception {
        return admins.json(admins.get(admin.token(), "/api/admin/review-queue" + query).andExpect(status().isOk()));
    }

    @Test
    void oldestWorkDateComesFirstAndThePairIsIncluded() throws Exception {
        long employee = admins.employee(tenant);
        LocalDate older = today().minusDays(3);
        LocalDate newer = today().minusDays(1);
        long newerId = admins.entry(tenant, employee, "CLOCK_IN", newer, at(newer, "08:00"), "PENDING_REVIEW",
                "SYNCED_LATE", 0);
        long olderId = admins.entry(tenant, employee, "CLOCK_IN", older, at(older, "08:00"), "PENDING_REVIEW",
                "OUTSIDE_GEOFENCE", 0);
        admins.entry(tenant, employee, "CLOCK_OUT", older, at(older, "17:00"), "VALID", null, null);

        JsonNode page = queue("");
        assertThat(page.get("totalItems").asInt()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("entry").get("id").asLong()).isEqualTo(olderId);
        assertThat(page.get("items").get(1).get("entry").get("id").asLong()).isEqualTo(newerId);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("pair").get("type").asText()).isEqualTo("CLOCK_OUT");
        assertThat(first.get("workDate").asText()).isEqualTo(older.toString());
        assertThat(first.get("ownEntry").asBoolean()).isFalse();
        assertThat(page.get("items").get(1).get("pair").isNull()).isTrue();
        assertThat(page.toString()).doesNotContain("latitude").doesNotContain("clientUuid");
    }

    @Test
    void pendingEntriesOlderThanThirtyOneDaysStillAppearAndMatchTheBadge() throws Exception {
        long employee = admins.employee(tenant);
        LocalDate ancient = today().minusDays(90);
        admins.entry(tenant, employee, "CLOCK_IN", ancient, at(ancient, "08:00"), "PENDING_REVIEW", "NO_GPS", 0);

        assertThat(queue("").get("totalItems").asInt()).isEqualTo(1);
        JsonNode count = admins.json(admins.get(admin.token(), "/api/admin/review-queue/count")
                .andExpect(status().isOk()));
        assertThat(count.get("pending").asInt()).isEqualTo(1);
        assertThat(count.get("today").asText()).isEqualTo(today().toString());
        assertThat(count.get("timeZone").asText()).isEqualTo("America/Costa_Rica");
    }

    @Test
    void pagingReportsTheTotalAndRejectsBadSizes() throws Exception {
        long employee = admins.employee(tenant);
        for (int i = 1; i <= 5; i++) {
            LocalDate day = today().minusDays(i);
            admins.entry(tenant, employee, "CLOCK_IN", day, at(day, "08:00"), "PENDING_REVIEW", "NO_GPS", 0);
        }
        JsonNode page = queue("?size=2&page=1");
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("totalItems").asInt()).isEqualTo(5);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(queue("?size=2&page=2").get("items")).hasSize(1);
        for (String bad : new String[] {"?size=101", "?size=0", "?page=-1", "?status=VALID", "?status=NOPE"}) {
            admins.get(admin.token(), "/api/admin/review-queue" + bad).andExpect(status().isBadRequest());
        }
        assertThat(queue("?size=100").get("items")).hasSize(5);
    }

    @Test
    void theRejectedFilterDateRangeAndEmployeeFilterNarrowTheList() throws Exception {
        long one = admins.employee(tenant);
        long two = admins.employee(tenant);
        LocalDate d1 = today().minusDays(2);
        LocalDate d2 = today().minusDays(1);
        admins.entry(tenant, one, "CLOCK_IN", d1, at(d1, "08:00"), "PENDING_REVIEW", "NO_GPS", 0);
        admins.entry(tenant, two, "CLOCK_IN", d2, at(d2, "08:00"), "PENDING_REVIEW", "NO_GPS", 0);
        admins.entry(tenant, two, "CLOCK_IN", d1, at(d1, "08:10"), "REJECTED", null, 10);

        assertThat(queue("?employeeId=" + one).get("totalItems").asInt()).isEqualTo(1);
        assertThat(queue("?from=" + d2 + "&to=" + d2).get("totalItems").asInt()).isEqualTo(1);
        JsonNode rejected = queue("?status=REJECTED");
        assertThat(rejected.get("totalItems").asInt()).isEqualTo(1);
        assertThat(rejected.get("items").get(0).get("entry").get("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void otherTenantsEntriesAreNeverListedOrCounted() throws Exception {
        TestData.Tenant other = data.tenant();
        long foreign = admins.employee(other);
        LocalDate d = today().minusDays(1);
        admins.entry(other, foreign, "CLOCK_IN", d, at(d, "08:00"), "PENDING_REVIEW", "NO_GPS", 0);

        assertThat(queue("").get("totalItems").asInt()).isZero();
        assertThat(admins.json(admins.get(admin.token(), "/api/admin/review-queue/count")).get("pending").asInt())
                .isZero();
        admins.get(admin.token(), "/api/admin/review-queue?employeeId=" + foreign).andExpect(status().isOk());
        assertThat(queue("?employeeId=" + foreign).get("totalItems").asInt()).isZero();
    }

    @Test
    void fromAndToOutsideTheAllowedRangeAreRejected() throws Exception {
        admins.get(admin.token(), "/api/admin/review-queue?from=1900-01-01").andExpect(status().isBadRequest());
        admins.get(admin.token(), "/api/admin/review-queue?to=" + today().plusDays(1)).andExpect(status().isBadRequest());
        admins.get(admin.token(), "/api/admin/review-queue?from=" + today().minusYears(5) + "&to=" + today())
                .andExpect(status().isOk());
    }

    @Test
    void theLowerBoundIsExactlyFiveYearsBack() throws Exception {
        admins.get(admin.token(), "/api/admin/review-queue?from=" + today().minusYears(5).minusDays(1))
                .andExpect(status().isBadRequest());
        admins.get(admin.token(), "/api/admin/review-queue?to=" + today().minusYears(5).minusDays(1))
                .andExpect(status().isBadRequest());
        admins.get(admin.token(), "/api/admin/review-queue?from=" + today().minusYears(5)).andExpect(status().isOk());
    }

    @Test
    void entriesOfTheSameWorkDateAreOrderedByEffectiveTimeNotByReceiptOrOrderOfInsertion() throws Exception {
        long first = admins.employee(tenant);
        long second = admins.employee(tenant);
        LocalDate day = today().minusDays(2);
        // Inserted first and received first, but its effective time is the later one.
        long laterEffective = admins.entry(tenant, first, "CLOCK_IN", day, at(day, "09:30"), "PENDING_REVIEW", "NO_GPS", 0);
        long earlierEffective = admins.entry(tenant, second, "CLOCK_IN", day, at(day, "08:10"), "PENDING_REVIEW", "NO_GPS", 0);
        jdbc.update("update time_entry set server_time = ? where id = ?", Timestamp.from(at(day, "08:00")), laterEffective);
        jdbc.update("update time_entry set server_time = ? where id = ?", Timestamp.from(at(day, "12:00")), earlierEffective);

        JsonNode items = queue("").get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("entry").get("id").asLong()).isEqualTo(earlierEffective);
        assertThat(items.get(1).get("entry").get("id").asLong()).isEqualTo(laterEffective);
    }
}
