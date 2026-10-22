package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import cr.asistencia.empleado.EmpleadoRepository;

/**
 * Lockout race (audit M-1). The PasswordEncoder spy holds every login inside the BCrypt compare until the
 * test releases them, which makes the interleaving deterministic: the lock is committed while N correct
 * guesses are already past the "is it locked?" check.
 */
@IntegrationTest
class LockoutRaceIntegrationTest {

    private static final int RAFAGA = 6;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private EmpleadoRepository empleados;

    private volatile CountDownLatch dentroDelCompare;
    private final CountDownLatch liberar = new CountDownLatch(1);

    @BeforeEach
    void retenerLosCompare() {
        doAnswer(inv -> {
            CountDownLatch llegada = dentroDelCompare;
            if (llegada != null) {
                llegada.countDown();
                liberar.await(20, TimeUnit.SECONDS);
            }
            return inv.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
    }

    @AfterEach
    void soltar() {
        dentroDelCompare = null;
        liberar.countDown();
    }

    private int login(TestData.Tenant t, TestData.Usuario u, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", t.empresaCodigo(),
                                "empleadoCodigo", u.codigo(), "pin", pin))))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> rafagaConLockMidCompare(TestData.Tenant t, TestData.Usuario u, String pin,
            boolean bloquearAMitad) throws Exception {
        dentroDelCompare = new CountDownLatch(RAFAGA);
        ExecutorService pool = Executors.newFixedThreadPool(RAFAGA);
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < RAFAGA; i++) {
                futuros.add(pool.submit(() -> login(t, u, pin)));
            }
            assertThat(dentroDelCompare.await(20, TimeUnit.SECONDS)).as("all attempts inside BCrypt").isTrue();
            if (bloquearAMitad) {
                jdbc.update("update empleado set bloqueado_hasta = now() + interval '15 minutes' where id = ?",
                        u.id());
            }
            liberar.countDown();
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> f : futuros) {
                estados.add(f.get(30, TimeUnit.SECONDS));
            }
            return estados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void correctPinsThatRaceAConcurrentLockGetNoTokenAndTheLockSurvives() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");

        List<Integer> estados = rafagaConLockMidCompare(t, u, u.pin(), true);

        assertThat(estados).hasSize(RAFAGA).containsOnly(401);
        assertThat(jdbc.queryForObject("select bloqueado_hasta > now() from empleado where id = ?", Boolean.class,
                u.id())).isTrue();
        dentroDelCompare = null;
        assertThat(login(t, u, u.pin())).isEqualTo(401);
    }

    @Test
    void controlWithoutTheLockTheSameBurstSucceeds() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");

        List<Integer> estados = rafagaConLockMidCompare(t, u, u.pin(), false);

        assertThat(estados).hasSize(RAFAGA).containsOnly(200);
    }

    @Test
    void aSuccessNeverClearsAnActiveLock() {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        jdbc.update("update empleado set intentos_fallidos = 3, bloqueado_hasta = now() + interval '10 minutes' "
                + "where id = ?", u.id());

        assertThat(empleados.registrarExito(u.id(), t.empresaId(), Instant.now())).isZero();

        assertThat(jdbc.queryForObject("select bloqueado_hasta > now() from empleado where id = ?", Boolean.class,
                u.id())).isTrue();
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                u.id())).isEqualTo(3);
    }

    @Test
    void aSuccessClearsAnExpiredLockAndTheCounter() {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        jdbc.update("update empleado set intentos_fallidos = 3, bloqueado_hasta = now() - interval '1 minute' "
                + "where id = ?", u.id());

        assertThat(empleados.registrarExito(u.id(), t.empresaId(), Instant.now())).isEqualTo(1);

        assertThat(jdbc.queryForObject("select bloqueado_hasta is null from empleado where id = ?", Boolean.class,
                u.id())).isTrue();
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                u.id())).isZero();
    }
}
