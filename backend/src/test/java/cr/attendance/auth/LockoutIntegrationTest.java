package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
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

    private static final String WRONG_PIN = "11112222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private int login(TestData.Tenant t, TestData.TestUser u, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("companyCode", t.companyCode(),
                                "employeeCode", u.code(), "pin", pin))))
                .andReturn().getResponse().getStatus();
    }

    private Integer attempts(long id) {
        return jdbc.queryForObject("select failed_attempts from employee where id = ?", Integer.class, id);
    }

    private Boolean locked(long id) {
        return jdbc.queryForObject("select locked_until is not null from employee where id = ?", Boolean.class, id);
    }

    @Test
    void fourFailuresThenSuccessResetsTheCounter() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        for (int i = 0; i < 4; i++) {
            assertThat(login(t, u, WRONG_PIN)).isEqualTo(401);
        }
        assertThat(attempts(u.id())).isEqualTo(4);
        assertThat(login(t, u, u.pin())).isEqualTo(200);
        assertThat(attempts(u.id())).isZero();
        assertThat(locked(u.id())).isFalse();
    }

    @Test
    void fiveFailuresLockEvenTheCorrectPinUntilTheLockExpires() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        for (int i = 0; i < 5; i++) {
            assertThat(login(t, u, WRONG_PIN)).isEqualTo(401);
        }
        assertThat(locked(u.id())).isTrue();
        assertThat(login(t, u, u.pin())).isEqualTo(401);

        jdbc.update("update employee set locked_until = now() - interval '1 minute' where id = ?", u.id());
        assertThat(login(t, u, u.pin())).isEqualTo(200);
        assertThat(locked(u.id())).isFalse();
    }

    @Test
    void lockedAccountIsNotExtendedByFurtherAttempts() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        for (int i = 0; i < 5; i++) {
            login(t, u, WRONG_PIN);
        }
        String before = jdbc.queryForObject("select locked_until::text from employee where id = ?", String.class,
                u.id());
        login(t, u, WRONG_PIN);
        String after = jdbc.queryForObject("select locked_until::text from employee where id = ?",
                String.class, u.id());
        assertThat(after).isEqualTo(before);
        assertThat(attempts(u.id())).isZero();
    }

    @Test
    void concurrentFailuresEndWithAConsistentLock() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Callable<Integer> task = () -> login(t, u, WRONG_PIN);
                futures.add(pool.submit(task));
            }
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(locked(u.id())).isTrue();
        assertThat(attempts(u.id())).isZero();
    }

    @Test
    void failuresOfOneEmployeeDoNotAffectAnother() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser a = data.user(t, "EMPLOYEE");
        TestData.TestUser b = data.user(t, "EMPLOYEE");
        for (int i = 0; i < 5; i++) {
            login(t, a, WRONG_PIN);
        }
        assertThat(login(t, b, b.pin())).isEqualTo(200);
    }
}
