package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
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
 * Lockout timing driven by the injected Clock (no sleeping, no direct DB edits of locked_until).
 * The clock starts at the real "now" because the JWT decoder validates exp against the system clock.
 */
@IntegrationTest
class LoginClockIntegrationTest {

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        void advance(Duration d) {
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

    private static final String WRONG_PIN = "11112222";

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
    private TestData.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        user = data.user(tenant, "EMPLOYEE");
    }

    private MvcResult attempt(String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", tenant.companyCode(),
                                "employeeCode", user.code(), "pin", pin))))
                .andReturn();
    }

    private int status(String pin) throws Exception {
        return attempt(pin).getResponse().getStatus();
    }

    private int attempts() {
        return jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                user.id());
    }

    private Instant lockedUntil() {
        return jdbc.queryForObject("select locked_until from employee where id = ?", java.sql.Timestamp.class,
                user.id()).toInstant();
    }

    private void lock() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(WRONG_PIN)).isEqualTo(401);
        }
    }

    @Test
    void lockIsExactlyFifteenMinutesFromTheFifthFailure() throws Exception {
        Instant t0 = clock.instant();
        lock();
        assertThat(lockedUntil()).isEqualTo(t0.plus(Duration.ofMinutes(15)));
    }

    @Test
    void stillLockedJustBeforeFifteenMinutesEvenWithTheCorrectPin() throws Exception {
        lock();
        Instant lock = lockedUntil();
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(status(user.pin())).isEqualTo(401);
        // Neither the correct nor a wrong PIN while locked touches the counter or extends the lock.
        assertThat(status(WRONG_PIN)).isEqualTo(401);
        assertThat(attempts()).isZero();
        assertThat(lockedUntil()).isEqualTo(lock);
    }

    @Test
    void lockedBodyIsIdenticalToWrongPinBody() throws Exception {
        String wrongPin = attempt(WRONG_PIN).getResponse().getContentAsString();
        for (int i = 0; i < 4; i++) {
            status(WRONG_PIN);
        }
        MvcResult locked = attempt(user.pin());
        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString()).isEqualTo(wrongPin);
    }

    @Test
    void unlocksAfterFifteenMinutesAndSuccessResetsState() throws Exception {
        lock();
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(status(user.pin())).isEqualTo(200);
        assertThat(attempts()).isZero();
        assertThat(jdbc.queryForObject("select locked_until is null from employee where id = ?", Boolean.class,
                user.id())).isTrue();
    }

    @Test
    void afterTheLockExpiresTheCounterStartsFromOneAndRelocksOnTheFifthFailure() throws Exception {
        lock();
        clock.advance(Duration.ofMinutes(16));
        assertThat(status(WRONG_PIN)).isEqualTo(401);
        assertThat(attempts()).isEqualTo(1);
        // Stale locked_until from the first lock must not count as locked.
        assertThat(status(WRONG_PIN)).isEqualTo(401);
        assertThat(attempts()).isEqualTo(2);
        for (int i = 0; i < 3; i++) {
            status(WRONG_PIN);
        }
        assertThat(attempts()).isZero();
        assertThat(lockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(status(user.pin())).isEqualTo(401);
    }

    @Test
    void sixthAttemptIsLockedEvenIfItWouldHaveBeenCorrect() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(WRONG_PIN)).isEqualTo(401);
        }
        assertThat(status(user.pin())).isEqualTo(401);
    }

    @Test
    void wrongFormatPinsCountAsFailures() throws Exception {
        for (String pin : new String[] {"abc", "1234", "１２３４５６７８", "48207315x"}) {
            assertThat(status(pin)).isEqualTo(401);
        }
        assertThat(attempts()).isEqualTo(4);
        assertThat(status("abcdefgh")).isEqualTo(401);
        assertThat(status(user.pin())).isEqualTo(401);
    }

    @Test
    void expiresAtInTheResponseFollowsTheInjectedClock() throws Exception {
        Instant t0 = clock.instant();
        String body = attempt(user.pin()).getResponse().getContentAsString();
        assertThat(Instant.parse(mapper.readTree(body).get("expiresAt").asText()))
                .isEqualTo(t0.plus(Duration.ofHours(8)));

        TestData.TestUser admin = data.user(tenant, "ADMIN");
        String adminBody = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", tenant.companyCode(),
                                "employeeCode", admin.code(), "pin", admin.pin()))))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(mapper.readTree(adminBody).get("expiresAt").asText()))
                .isEqualTo(t0.plus(Duration.ofHours(6)));
    }
}
