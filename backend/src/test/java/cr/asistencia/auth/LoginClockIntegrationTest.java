package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Lockout timing driven by the injected Clock (no sleeping, no direct DB edits of bloqueado_hasta).
 * The clock starts at the real "now" because the JWT decoder validates exp against the system clock.
 */
@IntegrationTest
class LoginClockIntegrationTest {

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        void avanzar(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    private static final String PIN_MALO = "11112222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MutableClock clock;

    private TestData.Tenant tenant;
    private TestData.Usuario usuario;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        usuario = data.usuario(tenant, "EMPLEADO");
    }

    private MvcResult intento(String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", tenant.empresaCodigo(),
                                "empleadoCodigo", usuario.codigo(), "pin", pin))))
                .andReturn();
    }

    private int estado(String pin) throws Exception {
        return intento(pin).getResponse().getStatus();
    }

    private int intentos() {
        return jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                usuario.id());
    }

    private Instant bloqueadoHasta() {
        return jdbc.queryForObject("select bloqueado_hasta from empleado where id = ?", java.sql.Timestamp.class,
                usuario.id()).toInstant();
    }

    private void bloquear() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(estado(PIN_MALO)).isEqualTo(401);
        }
    }

    @Test
    void lockIsExactlyFifteenMinutesFromTheFifthFailure() throws Exception {
        Instant t0 = clock.instant();
        bloquear();
        assertThat(bloqueadoHasta()).isEqualTo(t0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void stillLockedJustBeforeFifteenMinutesEvenWithTheCorrectPin() throws Exception {
        bloquear();
        Instant lock = bloqueadoHasta();
        clock.avanzar(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(estado(usuario.pin())).isEqualTo(401);
        // Neither the correct nor a wrong PIN while locked touches the counter or extends the lock.
        assertThat(estado(PIN_MALO)).isEqualTo(401);
        assertThat(intentos()).isZero();
        assertThat(bloqueadoHasta()).isEqualTo(lock);
    }

    @Test
    void lockedBodyIsIdenticalToWrongPinBody() throws Exception {
        String wrongPin = intento(PIN_MALO).getResponse().getContentAsString();
        for (int i = 0; i < 4; i++) {
            estado(PIN_MALO);
        }
        MvcResult locked = intento(usuario.pin());
        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString()).isEqualTo(wrongPin);
    }

    @Test
    void unlocksAfterFifteenMinutesAndSuccessResetsState() throws Exception {
        bloquear();
        clock.avanzar(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(estado(usuario.pin())).isEqualTo(200);
        assertThat(intentos()).isZero();
        assertThat(jdbc.queryForObject("select bloqueado_hasta is null from empleado where id = ?", Boolean.class,
                usuario.id())).isTrue();
    }

    @Test
    void afterTheLockExpiresTheCounterStartsFromOneAndRelocksOnTheFifthFailure() throws Exception {
        bloquear();
        clock.avanzar(Duration.ofMinutes(16));
        assertThat(estado(PIN_MALO)).isEqualTo(401);
        assertThat(intentos()).isEqualTo(1);
        // Stale bloqueado_hasta from the first lock must not count as locked.
        assertThat(estado(PIN_MALO)).isEqualTo(401);
        assertThat(intentos()).isEqualTo(2);
        for (int i = 0; i < 3; i++) {
            estado(PIN_MALO);
        }
        assertThat(intentos()).isZero();
        assertThat(bloqueadoHasta()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(estado(usuario.pin())).isEqualTo(401);
    }

    @Test
    void sixthAttemptIsLockedEvenIfItWouldHaveBeenCorrect() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(estado(PIN_MALO)).isEqualTo(401);
        }
        assertThat(estado(usuario.pin())).isEqualTo(401);
    }

    @Test
    void wrongFormatPinsCountAsFailures() throws Exception {
        for (String pin : new String[] {"abc", "1234", "１２３４５６７８", "48207315x"}) {
            assertThat(estado(pin)).isEqualTo(401);
        }
        assertThat(intentos()).isEqualTo(4);
        assertThat(estado("abcdefgh")).isEqualTo(401);
        assertThat(estado(usuario.pin())).isEqualTo(401);
    }

    @Test
    void expiraEnInTheResponseFollowsTheInjectedClock() throws Exception {
        Instant t0 = clock.instant();
        String body = intento(usuario.pin()).getResponse().getContentAsString();
        assertThat(Instant.parse(mapper.readTree(body).get("expiraEn").asText()))
                .isEqualTo(t0.plus(Duration.ofHours(8)));

        TestData.Usuario admin = data.usuario(tenant, "ADMIN");
        String adminBody = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", tenant.empresaCodigo(),
                                "empleadoCodigo", admin.codigo(), "pin", admin.pin()))))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(mapper.readTree(adminBody).get("expiraEn").asText()))
                .isEqualTo(t0.plus(Duration.ofHours(6)));
    }
}
