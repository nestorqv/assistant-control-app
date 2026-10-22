package cr.asistencia.seed;

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
        jdbc.update("delete from horario");
        jdbc.update("delete from empleado");
        jdbc.update("delete from sucursal");
        jdbc.update("delete from empresa where codigo = 'DEMO'");

        seeder.run(null);

        assertThat(output.getAll()).contains("Dev seed created: empresa DEMO, 7 empleados");
        assertThat(output.getAll()).doesNotContain(SEED_PIN).doesNotContain("$2a$").doesNotContain("$2b$");
        assertThat(jdbc.queryForObject("select count(*) from empleado", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from horario", Integer.class)).isEqualTo(35);
    }

    @Test
    void demoCompanyAndBranchMatchTheBlueprint() {
        Map<String, Object> empresa = jdbc.queryForMap(
                "select nombre, zona_horaria, activo from empresa where codigo = 'DEMO'");
        assertThat(empresa.get("nombre")).isEqualTo("Empresa Demo");
        assertThat(empresa.get("zona_horaria")).isEqualTo("America/Costa_Rica");
        assertThat(empresa.get("activo")).isEqualTo(true);

        Map<String, Object> sucursal = jdbc.queryForMap("select nombre, latitud, longitud, radio_metros from sucursal");
        assertThat(sucursal.get("nombre")).isEqualTo("Sucursal Centro");
        assertThat(((Number) sucursal.get("latitud")).doubleValue()).isEqualTo(9.9325);
        assertThat(((Number) sucursal.get("longitud")).doubleValue()).isEqualTo(-84.0796);
        assertThat(((Number) sucursal.get("radio_metros")).intValue()).isEqualTo(100);
    }

    @Test
    void sevenEmployeesWithTheDocumentedCodesNamesAndRoles() {
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select codigo, nombre, puesto, rol, activo, consentimiento_fecha is null as sin_consent, "
                        + "intentos_fallidos, bloqueado_hasta is null as libre, token_version, "
                        + "(sucursal_id is null) as sin_sucursal from empleado order by codigo");
        assertThat(filas).extracting(f -> f.get("codigo"))
                .containsExactly("1001", "1002", "1003", "1004", "1005", "1006", "9001");
        assertThat(filas).extracting(f -> f.get("nombre")).containsExactly("María Rojas", "José Vargas",
                "Ana Chaves", "Luis Mora", "Karla Solís", "Diego Arias", "Administrador Demo");
        assertThat(filas).extracting(f -> f.get("rol"))
                .containsExactly("EMPLEADO", "EMPLEADO", "EMPLEADO", "EMPLEADO", "EMPLEADO", "EMPLEADO", "ADMIN");
        assertThat(filas).allSatisfy(f -> {
            assertThat(f.get("activo")).isEqualTo(true);
            assertThat(f.get("sin_consent")).isEqualTo(true);
            assertThat(f.get("libre")).isEqualTo(true);
            assertThat(((Number) f.get("intentos_fallidos")).intValue()).isZero();
            assertThat(((Number) f.get("token_version")).intValue()).isZero();
        });
        // Only the admin has no sucursal.
        assertThat(filas).filteredOn(f -> Boolean.TRUE.equals(f.get("sin_sucursal")))
                .extracting(f -> f.get("codigo")).containsExactly("9001");
    }

    @Test
    void schedulesAreMondayToFridayWithFiveMinutesTolerance() {
        assertThat(jdbc.queryForObject("select count(*) from horario where tolerancia_min <> 5", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from horario where dia_semana not between 1 and 5",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(distinct dia_semana) from horario", Integer.class))
                .isEqualTo(5);
    }

    // Regression: LocalTime values used to be stored shifted by the JVM offset on non-UTC machines (e.g.
    // America/Costa_Rica). See HorarioTimeZoneIntegrationTest for the deterministic reproduction.
    @Test
    void seededScheduleHoursAreStoredAsWrittenWhateverTheJvmZone() {
        assertThat(jdbc.queryForList("""
                select distinct h.hora_entrada::text || '-' || h.hora_salida::text from horario h
                  join empleado e on e.id = h.empleado_id where e.codigo = '1005'
                """, String.class)).containsExactly("06:00:00-14:00:00");
        assertThat(jdbc.queryForObject("""
                select count(*) from horario h join empleado e on e.id = h.empleado_id
                 where e.codigo <> '1005' and h.hora_entrada = '08:00' and h.hora_salida = '17:00'
                """, Integer.class)).isEqualTo(30);
    }

    @Test
    void everyDemoUserCanLogInAndTheAdminSeesAllSevenEmployees() throws Exception {
        for (String codigo : List.of("1001", "1002", "1003", "1004", "1005", "1006", "9001")) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("empresaCodigo", "demo",
                                    "empleadoCodigo", codigo, "pin", SEED_PIN))))
                    .andExpect(status().isOk());
        }
        String token = mapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", "DEMO",
                                "empleadoCodigo", "9001", "pin", SEED_PIN))))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/admin/empleados").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void aWrongSeedPinDoesNotLogIn() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", "DEMO",
                                "empleadoCodigo", "1001", "pin", "11112222"))))
                .andExpect(status().isUnauthorized());
    }
}
