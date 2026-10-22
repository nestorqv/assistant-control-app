package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class LockoutIntegrationTest {

    private static final String PIN_MALO = "11112222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private int login(TestData.Tenant t, TestData.Usuario u, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", t.empresaCodigo(),
                                "empleadoCodigo", u.codigo(), "pin", pin))))
                .andReturn().getResponse().getStatus();
    }

    private Integer intentos(long id) {
        return jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class, id);
    }

    private Boolean bloqueado(long id) {
        return jdbc.queryForObject("select bloqueado_hasta is not null from empleado where id = ?", Boolean.class, id);
    }

    @Test
    void fourFailuresThenSuccessResetsTheCounter() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        for (int i = 0; i < 4; i++) {
            assertThat(login(t, u, PIN_MALO)).isEqualTo(401);
        }
        assertThat(intentos(u.id())).isEqualTo(4);
        assertThat(login(t, u, u.pin())).isEqualTo(200);
        assertThat(intentos(u.id())).isZero();
        assertThat(bloqueado(u.id())).isFalse();
    }

    @Test
    void fiveFailuresLockEvenTheCorrectPinUntilTheLockExpires() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        for (int i = 0; i < 5; i++) {
            assertThat(login(t, u, PIN_MALO)).isEqualTo(401);
        }
        assertThat(bloqueado(u.id())).isTrue();
        assertThat(login(t, u, u.pin())).isEqualTo(401);

        jdbc.update("update empleado set bloqueado_hasta = now() - interval '1 minute' where id = ?", u.id());
        assertThat(login(t, u, u.pin())).isEqualTo(200);
        assertThat(bloqueado(u.id())).isFalse();
    }

    @Test
    void lockedAccountIsNotExtendedByFurtherAttempts() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        for (int i = 0; i < 5; i++) {
            login(t, u, PIN_MALO);
        }
        String antes = jdbc.queryForObject("select bloqueado_hasta::text from empleado where id = ?", String.class,
                u.id());
        login(t, u, PIN_MALO);
        String despues = jdbc.queryForObject("select bloqueado_hasta::text from empleado where id = ?",
                String.class, u.id());
        assertThat(despues).isEqualTo(antes);
        assertThat(intentos(u.id())).isZero();
    }

    @Test
    void concurrentFailuresEndWithAConsistentLock() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Callable<Integer> task = () -> login(t, u, PIN_MALO);
                futures.add(pool.submit(task));
            }
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(bloqueado(u.id())).isTrue();
        assertThat(intentos(u.id())).isZero();
    }

    @Test
    void failuresOfOneEmployeeDoNotAffectAnother() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario a = data.usuario(t, "EMPLEADO");
        TestData.Usuario b = data.usuario(t, "EMPLEADO");
        for (int i = 0; i < 5; i++) {
            login(t, a, PIN_MALO);
        }
        assertThat(login(t, b, b.pin())).isEqualTo(200);
    }
}
