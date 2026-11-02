package cr.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.MutableClock;
import cr.attendance.support.MutableClockConfig;
import cr.attendance.support.TestData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The history keeps "no value" as SQL NULL (old) or an empty string (new), and the API returns it as such. */
@IntegrationTest
@Import(MutableClockConfig.class)
class AdjustmentValuesIntegrationTest {

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
    void eightInTheEvening() {
        clock.advanceToNext(CR, LocalTime.of(20, 0));
    }

    private LocalDate today() {
        return clock.instant().atZone(CR).toLocalDate();
    }

    @Test
    void correctingAnEntryWhoseScheduleWasRemovedStoresAnEmptyNewLatenessNotTheTextNull() throws Exception {
        TestData.Tenant tenant = data.tenant();
        AdminClient.Admin admin = admins.admin(tenant);
        long employee = admins.employee(tenant);
        // Recorded as 17 minutes late; the employee has no schedule for this weekday now.
        long id = admins.entry(tenant, employee, "CLOCK_IN", today(), today().atTime(8, 17).atZone(CR).toInstant(),
                "VALID", null, 17);
        String effective = admins.json(admins.get(admin.token(), "/api/admin/time-entries/" + id))
                .get("effectiveTime").asText();

        JsonNode body = admins.json(admins.post(admin.token(), "/api/admin/time-entries/" + id + "/correct",
                Map.of("reason", "Hora del teléfono incorrecta", "expectedStatus", "VALID", "expectedEffectiveTime",
                        effective, "localTime", today() + "T08:03")).andExpect(status().isOk()));

        JsonNode late = null;
        for (JsonNode adjustment : body.get("adjustments")) {
            if ("late_minutes".equals(adjustment.get("field").asText())) {
                late = adjustment;
            }
        }
        assertThat(late).isNotNull();
        assertThat(late.get("oldValue").asText()).isEqualTo("17");
        assertThat(late.get("newValue").isTextual()).isTrue();
        assertThat(late.get("newValue").asText()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select new_value from adjustment where time_entry_id = ? and field = 'late_minutes'", String.class, id))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "select late_minutes is null from time_entry where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void createdEntriesRecordNoOldValueAsSqlNull() throws Exception {
        TestData.Tenant tenant = data.tenant();
        AdminClient.Admin admin = admins.admin(tenant);
        long employee = admins.employee(tenant);
        JsonNode created = admins.json(admins.post(admin.token(), "/api/admin/time-entries",
                Map.of("employeeId", employee, "type", "CLOCK_IN", "workDate", today().toString(), "localTime",
                        today() + "T07:00", "reason", "Falla de la cámara o de la ubicación"))
                .andExpect(status().isCreated()));
        long id = created.get("id").asLong();
        assertThat(jdbc.queryForObject("select old_value is null from adjustment where time_entry_id = ?",
                Boolean.class, id)).isTrue();
        JsonNode view = created.get("adjustments").get(0);
        assertThat(view.get("oldValue").isNull()).isTrue();
        assertThat(view.get("action").asText()).isEqualTo("CREATE_ENTRY");
    }
}
