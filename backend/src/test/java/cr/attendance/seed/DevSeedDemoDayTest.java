package cr.attendance.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import cr.attendance.support.MutableClock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The opt-in demo day (DEV_SEED_DEMO_DAY=true): today's blueprint entries, added once. */
@SpringBootTest(properties = {"app.seed.pin=" + DevSeedDemoDayTest.SEED_PIN, "app.seed.demo-day=true"})
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class DevSeedDemoDayTest {

    static final String SEED_PIN = "26481935";

    /** The clock is pinned to a Wednesday at 09:00 in Costa Rica before the seeder runs, so the KPIs never depend on the day the suite runs. */
    @TestConfiguration
    static class WednesdayMorning {
        @Bean
        @Primary
        MutableClock pinnedClock() {
            MutableClock clock = new MutableClock();
            clock.advanceToNext(ZoneId.of("America/Costa_Rica"), DayOfWeek.WEDNESDAY, LocalTime.of(9, 0));
            return clock;
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private DevDataSeeder seeder;

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void todaysEntriesReproduceTheBlueprintAndASecondRunAddsNothing(CapturedOutput output) throws Exception {
        assertThat(count("select count(*) from time_entry")).isEqualTo(10);
        assertThat(count("select count(*) from time_entry where status = 'PENDING_REVIEW' "
                + "and review_reasons = 'OUTSIDE_GEOFENCE' and distance_meters between 419 and 421")).isEqualTo(1);
        // Every MOBILE entry has a placeholder selfie and a GPS row; the tablet entry and the admin have none.
        assertThat(count("select count(*) from evidence where type = 'PHOTO' and storage_key is not null")).isEqualTo(9);
        assertThat(count("select count(*) from evidence where type = 'GPS'")).isEqualTo(9);
        assertThat(count("select count(*) from time_entry where source = 'TABLET'")).isEqualTo(1);
        assertThat(count("select count(*) from time_entry where server_time <> effective_time")).isZero();

        seeder.run(null);
        assertThat(count("select count(*) from time_entry")).isEqualTo(10);
        // Counts only in the log: no names, no coordinates.
        assertThat(output.getAll()).contains("Dev seed demo day: today already has entries")
                .doesNotContain(SEED_PIN).doesNotContain("María Rojas");
    }

    @Test
    void theAdminDashboardShowsNineEmployeesPresentAndOneToReview() throws Exception {
        String token = mapper.readTree(mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", "DEMO", "employeeCode", "9001",
                                "pin", SEED_PIN)))).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode body = mapper.readTree(mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(body.get("kpis").get("present").asInt()).isEqualTo(9);
        assertThat(body.get("kpis").get("toReview").asInt()).isEqualTo(1);
        assertThat(body.get("rows")).hasSize(12);
        // Late arrivals are measured against the Monday to Friday schedules.
        assertThat(body.get("kpis").get("late").asInt()).isEqualTo(2);
        assertThat(body.get("kpis").get("expected").asInt()).isEqualTo(12);
        assertThat(body.get("kpis").get("notClocked").asInt()).isEqualTo(3);
    }
}
