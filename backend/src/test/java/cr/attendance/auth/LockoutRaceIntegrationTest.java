package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
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
import cr.attendance.employee.EmployeeRepository;

/**
 * Lockout race (audit M-1). The PasswordEncoder spy holds every login inside the BCrypt compare until the
 * test releases them, which makes the interleaving deterministic: the lock is committed while N correct
 * guesses are already past the "is it locked?" check.
 */
@IntegrationTest
class LockoutRaceIntegrationTest {

    private static final int BURST = 6;

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
    private EmployeeRepository employees;

    private volatile CountDownLatch insideCompare;
    private final CountDownLatch releaseLatch = new CountDownLatch(1);

    @BeforeEach
    void holdCompares() {
        doAnswer(inv -> {
            CountDownLatch arrived = insideCompare;
            if (arrived != null) {
                arrived.countDown();
                releaseLatch.await(20, TimeUnit.SECONDS);
            }
            return inv.callRealMethod();
        }).when(passwordEncoder).matches(any(), any());
    }

    @AfterEach
    void release() {
        insideCompare = null;
        releaseLatch.countDown();
    }

    private int login(TestData.Tenant t, TestData.TestUser u, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", t.companyCode(),
                                "employeeCode", u.code(), "pin", pin))))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> burstWithLockMidCompare(TestData.Tenant t, TestData.TestUser u, String pin,
            boolean lockMidway) throws Exception {
        insideCompare = new CountDownLatch(BURST);
        ExecutorService pool = Executors.newFixedThreadPool(BURST);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < BURST; i++) {
                futures.add(pool.submit(() -> login(t, u, pin)));
            }
            assertThat(insideCompare.await(20, TimeUnit.SECONDS)).as("all attempts inside BCrypt").isTrue();
            if (lockMidway) {
                jdbc.update("update employee set locked_until = now() + interval '15 minutes' where id = ?",
                        u.id());
            }
            releaseLatch.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void correctPinsThatRaceAConcurrentLockGetNoTokenAndTheLockSurvives() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");

        List<Integer> statuses = burstWithLockMidCompare(t, u, u.pin(), true);

        assertThat(statuses).hasSize(BURST).containsOnly(401);
        assertThat(jdbc.queryForObject("select locked_until > now() from employee where id = ?", Boolean.class,
                u.id())).isTrue();
        insideCompare = null;
        assertThat(login(t, u, u.pin())).isEqualTo(401);
    }

    @Test
    void controlWithoutTheLockTheSameBurstSucceeds() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");

        List<Integer> statuses = burstWithLockMidCompare(t, u, u.pin(), false);

        assertThat(statuses).hasSize(BURST).containsOnly(200);
    }

    @Test
    void aSuccessNeverClearsAnActiveLock() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set failed_attempts = 3, locked_until = now() + interval '10 minutes' "
                + "where id = ?", u.id());

        assertThat(employees.recordSuccessfulLogin(u.id(), t.companyId(), Instant.now())).isZero();

        assertThat(jdbc.queryForObject("select locked_until > now() from employee where id = ?", Boolean.class,
                u.id())).isTrue();
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                u.id())).isEqualTo(3);
    }

    @Test
    void aSuccessClearsAnExpiredLockAndTheCounter() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        jdbc.update("update employee set failed_attempts = 3, locked_until = now() - interval '1 minute' "
                + "where id = ?", u.id());

        assertThat(employees.recordSuccessfulLogin(u.id(), t.companyId(), Instant.now())).isEqualTo(1);

        assertThat(jdbc.queryForObject("select locked_until is null from employee where id = ?", Boolean.class,
                u.id())).isTrue();
        assertThat(jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class,
                u.id())).isZero();
    }
}
