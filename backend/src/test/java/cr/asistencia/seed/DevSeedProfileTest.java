package cr.asistencia.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empleado.HorarioRepository;
import cr.asistencia.empresa.EmpresaRepository;
import cr.asistencia.empresa.SucursalRepository;
import cr.asistencia.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class DevSeedProfileTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void testProfileHasNoSeederAndNoDemoEmpresa() {
        assertThat(context.getBeanNamesForType(DevDataSeeder.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from empresa where codigo = 'DEMO'", Integer.class))
                .isZero();
    }

    private static DevDataSeeder seeder(MockEnvironment env, String pin, EmpresaRepository empresas) {
        return new DevDataSeeder(env, new SeedProperties(pin), empresas, mock(SucursalRepository.class),
                mock(EmpleadoRepository.class), mock(HorarioRepository.class), mock(PasswordEncoder.class));
    }

    private static MockEnvironment entorno(String... perfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(perfiles);
        return env;
    }

    @Test
    void seederRefusesToRunUnlessDevIsExplicitlyActive() {
        EmpresaRepository empresas = mock(EmpresaRepository.class);
        for (MockEnvironment env : new MockEnvironment[] {entorno(), entorno("test"), entorno("staging")}) {
            DevDataSeeder seeder = seeder(env, "59173842", empresas);
            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev profile");
        }
        verifyNoInteractions(empresas);
    }

    @Test
    void invalidOrMissingPinFailsWithTheDocumentedMessage() {
        EmpresaRepository empresas = mock(EmpresaRepository.class);
        for (String pin : new String[] {null, "", "12345678", "1234"}) {
            DevDataSeeder seeder = seeder(entorno("dev"), pin, empresas);
            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(DevDataSeeder.MENSAJE_PIN);
        }
        verifyNoInteractions(empresas);
    }

    @Test
    void prodProfileAbortsTheSeeder() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "prod");
        EmpresaRepository empresas = mock(EmpresaRepository.class);
        DevDataSeeder seeder = seeder(env, "59173842", empresas);
        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        verifyNoInteractions(empresas);
    }
}
