package cr.attendance.seed;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.company.BranchRepository;
import cr.attendance.company.CompanyRepository;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkScheduleRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** DEV_SEED_LAT / LNG / RADIUS: a fresh seed uses them and an existing branch is updated on the next start. */
@SpringBootTest(properties = {"app.seed.pin=" + DevSeedLocationTest.SEED_PIN,
    "app.seed.branch-latitude=10.5", "app.seed.branch-longitude=-83.25", "app.seed.branch-radius-meters=250"})
@AutoConfigureMockMvc
@AutoConfigureEmbeddedDatabase(type = DatabaseType.POSTGRES, provider = DatabaseProvider.ZONKY)
@ActiveProfiles({"test", "dev"})
@ExtendWith(OutputCaptureExtension.class)
class DevSeedLocationTest {

    // Test-only value, valid under PinPolicy.
    static final String SEED_PIN = "64820517";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private BranchRepository branches;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private WorkScheduleRepository schedules;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Map<String, Object> branch() {
        return jdbc.queryForMap("select latitude, longitude, radius_meters from branch");
    }

    @Test
    void aFreshSeedUsesTheConfiguredLocationAndALaterRunUpdatesTheExistingBranch(CapturedOutput output) {
        Map<String, Object> fresh = branch();
        assertThat(((Number) fresh.get("latitude")).doubleValue()).isEqualTo(10.5);
        assertThat(((Number) fresh.get("longitude")).doubleValue()).isEqualTo(-83.25);
        assertThat(((Number) fresh.get("radius_meters")).intValue()).isEqualTo(250);

        // The next dev start has different values: the already-seeded branch must follow them.
        SeedProperties changed = new SeedProperties(SEED_PIN, 9.75, -84.5, 400);
        new DevDataSeeder(environment, changed, companies, branches, employees, schedules, passwordEncoder)
                .run(null);

        Map<String, Object> updated = branch();
        assertThat(((Number) updated.get("latitude")).doubleValue()).isEqualTo(9.75);
        assertThat(((Number) updated.get("longitude")).doubleValue()).isEqualTo(-84.5);
        assertThat(((Number) updated.get("radius_meters")).intValue()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from employee", Integer.class)).isEqualTo(7);

        assertThat(output.getAll()).contains("Dev seed: branch location updated")
                .doesNotContain(SEED_PIN);
    }
}
