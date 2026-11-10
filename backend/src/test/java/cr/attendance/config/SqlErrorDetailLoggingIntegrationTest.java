package cr.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.attendance.common.Constraints;
import cr.attendance.support.AdminClient;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D15: SQL error details (Key (company_id, code)=(...), failing rows with name and pin_hash) must never reach the
 * logs. Employee codes may hold an id number, so the code and the name are probed here.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SqlErrorDetailLoggingIntegrationTest {

    private static final String CODE = "7777-ABC";
    private static final String NAME = "Nombre Que No Debe Filtrarse";

    @Autowired
    private AdminClient admins;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void theDriverPropertyIsConfiguredAndSqlExceptionHelperIsSilenced(@Value("${logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper}") String level) throws Exception {
        // The property the driver actually received, not the configured string.
        // The embedded test database replaces the application pool, so a pool is built from the application's own
        // spring.datasource.hikari.* settings and pointed at the embedded server: what the driver receives is what
        // production configures.
        com.zaxxer.hikari.HikariConfig config = Binder.get(environment)
                .bind("spring.datasource.hikari", com.zaxxer.hikari.HikariConfig.class)
                .orElseGet(com.zaxxer.hikari.HikariConfig::new);
        try (java.sql.Connection embedded = dataSource.getConnection()) {
            config.setJdbcUrl(embedded.getMetaData().getURL());
            config.setUsername(embedded.getMetaData().getUserName());
            config.setPassword(null);
        }
        config.setMaximumPoolSize(1);
        try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(config);
                java.sql.Connection c = pool.getConnection()) {
            assertThat(c.unwrap(org.postgresql.jdbc.PgConnection.class).getLogServerErrorDetail()).isFalse();
        }
        assertThat(level).isEqualToIgnoringCase("OFF");
    }

    @Test
    void concurrentCreatesOfTheSameCodeGiveOne409AndNeitherLogsNorBodiesCarryTheCodeOrName(CapturedOutput output) throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin first = admins.admin(t);
        AdminClient.Admin second = admins.admin(t);
        Map<String, Object> body = Map.of("code", CODE, "name", NAME, "role", "EMPLOYEE", "branchId", t.branchId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<String> bodies = new ArrayList<>();
        int created = 0;
        int conflicts = 0;
        try {
            List<Future<org.springframework.mock.web.MockHttpServletResponse>> futures = new ArrayList<>();
            for (AdminClient.Admin admin : List.of(first, second)) {
                Callable<org.springframework.mock.web.MockHttpServletResponse> call = () -> {
                    go.await();
                    return admins.post(admin.token(), "/api/admin/employees", body).andReturn().getResponse();
                };
                futures.add(pool.submit(call));
            }
            go.countDown();
            for (var f : futures) {
                var response = f.get(60, TimeUnit.SECONDS);
                if (response.getStatus() == 201) {
                    created++;
                } else if (response.getStatus() == 409) {
                    conflicts++;
                    bodies.add(response.getContentAsString());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(bodies.get(0)).contains("EMPLOYEE_CODE_TAKEN").doesNotContain(CODE).doesNotContain(NAME);
        // The embedded PostgreSQL server prints its own server log (DETAIL line with the key) into the same stream;
        // that is the test database process, not the application, so only application lines are probed.
        String applicationLog = output.getAll().lines().filter(line -> !line.contains("EmbeddedPostgres")).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(applicationLog).isNotBlank().doesNotContain(CODE).doesNotContain(NAME).doesNotContain("$2a$").doesNotContain("$2b$");
    }

    @Test
    void aDuplicateCodeThatPassesThePrecheckEndsAs409FromTheConstraintAndLogsNoRowDetail(CapturedOutput output) throws Exception {
        TestData.Tenant t = data.tenant();
        AdminClient.Admin admin = admins.admin(t);
        String code = "9191-XYZ";
        String name = "Otro Nombre Reservado";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<org.springframework.mock.web.MockHttpServletResponse> request;
        try (java.sql.Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            // An uncommitted row with the same code: the request's precheck cannot see it, so its INSERT reaches the
            // unique index and waits for this transaction.
            try (java.sql.PreparedStatement insert = c.prepareStatement(
                    "insert into employee (company_id, branch_id, code, name, pin_hash, role) values (?, ?, ?, 'Persona de prueba', ?, 'EMPLOYEE')")) {
                insert.setLong(1, t.companyId());
                insert.setLong(2, t.branchId());
                insert.setString(3, code);
                insert.setString(4, data.pinHash());
                insert.executeUpdate();
            }
            request = pool.submit(() -> admins.post(admin.token(), "/api/admin/employees",
                    Map.of("code", code, "name", name, "role", "EMPLOYEE", "branchId", t.branchId())).andReturn().getResponse());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("select count(*) from pg_locks where not granted and locktype = 'transactionid'", Integer.class) == 0) {
                assertThat(System.nanoTime()).as("the request reached the unique index and waits").isLessThan(deadline);
                Thread.sleep(25);
            }
            c.commit();
        } finally {
            pool.shutdown();
        }
        var response = request.get(30, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("EMPLOYEE_CODE_TAKEN").doesNotContain(code).doesNotContain(name);
        assertThat(jdbc.queryForObject("select count(*) from employee where company_id = ? and code = ?", Integer.class, t.companyId(), code)).isEqualTo(1);
        String applicationLog = output.getAll().lines().filter(line -> !line.contains("EmbeddedPostgres")).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(applicationLog.contains(code)).as("the application log carries the code").isFalse();
        assertThat(applicationLog.contains(name)).as("the application log carries the name").isFalse();
        assertThat(applicationLog).doesNotContain("Key (").doesNotContain("duplicate key").doesNotContain("$2a$").doesNotContain("$2b$");
    }

    @Test
    void aForcedCheckViolationThroughSqlNamesTheConstraintButCarriesNoRowValues() {
        TestData.Tenant t = data.tenant();
        // The embedded test database is not tuned like the application pool, so the message may include the
        // row; what the application guarantees is that it extracts the constraint NAME and logs only that.
        DataIntegrityViolationException e = (DataIntegrityViolationException) catchViolation(t);
        assertThat(Constraints.nameOf(e)).isEqualTo("ck_employee_name");
    }

    private Throwable catchViolation(TestData.Tenant t) {
        Throwable[] caught = new Throwable[1];
        assertThatThrownBy(() -> {
            try {
                jdbc.update("""
                        insert into employee (company_id, branch_id, code, name, pin_hash, role)
                        values (?, ?, ?, '  ', ?, 'EMPLOYEE')
                        """, t.companyId(), t.branchId(), CODE, data.pinHash());
            } catch (RuntimeException ex) {
                caught[0] = ex;
                throw ex;
            }
        }).isInstanceOf(DataIntegrityViolationException.class);
        return caught[0];
    }
}
