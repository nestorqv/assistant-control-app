package cr.asistencia.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
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

/** Own context and own database: the only place where global row counts are asserted. */
@SpringBootTest(properties = "app.seed.pin=" + DevSeedEnabledTest.SEED_PIN)
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class DevSeedEnabledTest {

    // Test-only value, valid under PinPolicy.
    static final String SEED_PIN = "59173842";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DevDataSeeder seeder;

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void seedCreatesTheDemoCompanyIdempotentlyAndLoginWorks(CapturedOutput output) throws Exception {
        assertThat(count("select count(*) from empresa where codigo = 'DEMO'")).isEqualTo(1);
        assertThat(count("select count(*) from sucursal")).isEqualTo(1);
        assertThat(count("select count(*) from empleado")).isEqualTo(7);
        assertThat(count("select count(*) from empleado where rol = 'ADMIN' and sucursal_id is null")).isEqualTo(1);
        assertThat(count("select count(*) from horario")).isEqualTo(35);
        assertThat(count("select count(*) from empleado where pin_hash like '$2%'")).isEqualTo(7);

        seeder.run(null);
        assertThat(count("select count(*) from empresa where codigo = 'DEMO'")).isEqualTo(1);
        assertThat(count("select count(*) from empleado")).isEqualTo(7);
        assertThat(count("select count(*) from horario")).isEqualTo(35);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empresaCodigo\":\"DEMO\",\"empleadoCodigo\":\"1001\",\"pin\":\"" + SEED_PIN + "\"}"))
                .andExpect(status().isOk());

        // The second seeder run above logs too; the PIN must never appear in any captured output.
        assertThat(output.getAll()).doesNotContain(SEED_PIN);
        assertThat(output.getAll()).contains("Dev seed already present");
    }
}
