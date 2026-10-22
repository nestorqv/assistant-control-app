package cr.attendance.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Content of the dev seed against the blueprint, plus the startup log. Uses a different seed PIN from
 * DevSeedEnabledTest so it gets its own context and its own embedded database.
 */
@SpringBootTest(properties = "app.seed.pin=" + DevSeedContentTest.SEED_PIN)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class DevSeedContentTest {

    // Test-only value, valid under PinPolicy.
    static final String SEED_PIN = "73920518";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private DevDataSeeder seeder;

    /**
     * The startup log is emitted before the output capture starts, so this wipes the seed (no marks exist
     * in this database) and runs the seeder again inside the test. The end state equals the original seed.
     */
    @Test
    void seedingFromScratchLogsTheCreationLineAndNeverThePinOrAHash(CapturedOutput output) throws Exception {
        jdbc.update("delete from work_schedule");
        jdbc.update("delete from employee");
        jdbc.update("delete from branch");
        jdbc.update("delete from company where code = 'DEMO'");

        seeder.run(null);

        assertThat(output.getAll()).contains("Dev seed created: company DEMO, 7 employees");
        assertThat(output.getAll()).doesNotContain(SEED_PIN).doesNotContain("$2a$").doesNotContain("$2b$");
        assertThat(jdbc.queryForObject("select count(*) from employee", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from work_schedule", Integer.class)).isEqualTo(35);
    }

    @Test
    void demoCompanyAndBranchMatchTheBlueprint() {
        Map<String, Object> company = jdbc.queryForMap(
                "select name, time_zone, active from company where code = 'DEMO'");
        assertThat(company.get("name")).isEqualTo("Empresa Demo");
        assertThat(company.get("time_zone")).isEqualTo("America/Costa_Rica");
        assertThat(company.get("active")).isEqualTo(true);

        Map<String, Object> branch = jdbc.queryForMap("select name, latitude, longitude, radius_meters from branch");
        assertThat(branch.get("name")).isEqualTo("Sucursal Centro");
        assertThat(((Number) branch.get("latitude")).doubleValue()).isEqualTo(9.9325);
        assertThat(((Number) branch.get("longitude")).doubleValue()).isEqualTo(-84.0796);
        assertThat(((Number) branch.get("radius_meters")).intValue()).isEqualTo(100);
    }

    @Test
    void sevenEmployeesWithTheDocumentedCodesNamesAndRoles() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select code, name, job_title, role, active, consent_at is null as no_consent, "
                        + "failed_attempts, locked_until is null as unlocked, token_version, "
                        + "(branch_id is null) as no_branch from employee order by code");
        assertThat(rows).extracting(f -> f.get("code"))
                .containsExactly("1001", "1002", "1003", "1004", "1005", "1006", "9001");
        assertThat(rows).extracting(f -> f.get("name")).containsExactly("María Rojas", "José Vargas",
                "Ana Chaves", "Luis Mora", "Karla Solís", "Diego Arias", "Administrador Demo");
        assertThat(rows).extracting(f -> f.get("role"))
                .containsExactly("EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "EMPLOYEE", "ADMIN");
        assertThat(rows).allSatisfy(f -> {
            assertThat(f.get("active")).isEqualTo(true);
            assertThat(f.get("no_consent")).isEqualTo(true);
            assertThat(f.get("unlocked")).isEqualTo(true);
            assertThat(((Number) f.get("failed_attempts")).intValue()).isZero();
            assertThat(((Number) f.get("token_version")).intValue()).isZero();
        });
        // Only the admin has no branch.
        assertThat(rows).filteredOn(f -> Boolean.TRUE.equals(f.get("no_branch")))
                .extracting(f -> f.get("code")).containsExactly("9001");
    }

    @Test
    void schedulesAreMondayToFridayWithFiveMinutesTolerance() {
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where grace_minutes <> 5", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from work_schedule where day_of_week not between 1 and 5",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(distinct day_of_week) from work_schedule", Integer.class))
                .isEqualTo(5);
    }

    // Regression: LocalTime values used to be stored shifted by the JVM offset on non-UTC machines (e.g.
    // America/Costa_Rica). See WorkScheduleTimeZoneIntegrationTest for the deterministic reproduction.
    @Test
    void seededScheduleHoursAreStoredAsWrittenWhateverTheJvmZone() {
        assertThat(jdbc.queryForList("""
                select distinct h.start_time::text || '-' || h.end_time::text from work_schedule h
                  join employee e on e.id = h.employee_id where e.code = '1005'
                """, String.class)).containsExactly("06:00:00-14:00:00");
        assertThat(jdbc.queryForObject("""
                select count(*) from work_schedule h join employee e on e.id = h.employee_id
                 where e.code <> '1005' and h.start_time = '08:00' and h.end_time = '17:00'
                """, Integer.class)).isEqualTo(30);
    }

    @Test
    void everyDemoUserCanLogInAndTheAdminSeesAllSevenEmployees() throws Exception {
        for (String code : List.of("1001", "1002", "1003", "1004", "1005", "1006", "9001")) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("companyCode", "demo",
                                    "employeeCode", code, "pin", SEED_PIN))))
                    .andExpect(status().isOk());
        }
        String token = mapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", "DEMO",
                                "employeeCode", "9001", "pin", SEED_PIN))))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/admin/employees").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void aWrongSeedPinDoesNotLogIn() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", "DEMO",
                                "employeeCode", "1001", "pin", "11112222"))))
                .andExpect(status().isUnauthorized());
    }
}
