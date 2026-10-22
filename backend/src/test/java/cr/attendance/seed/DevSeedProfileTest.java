package cr.attendance.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.company.CompanyRepository;
import cr.attendance.company.BranchRepository;
import cr.attendance.support.IntegrationTest;
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
    void testProfileHasNoSeederAndNoDemoCompany() {
        assertThat(context.getBeanNamesForType(DevDataSeeder.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from company where code = 'DEMO'", Integer.class))
                .isZero();
    }

    private static DevDataSeeder seeder(MockEnvironment env, String pin, CompanyRepository companies) {
        return new DevDataSeeder(env, new SeedProperties(pin), companies, mock(BranchRepository.class),
                mock(EmployeeRepository.class), mock(WorkScheduleRepository.class), mock(PasswordEncoder.class));
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void seederRefusesToRunUnlessDevIsExplicitlyActive() {
        CompanyRepository companies = mock(CompanyRepository.class);
        for (MockEnvironment env : new MockEnvironment[] {environment(), environment("test"), environment("staging")}) {
            DevDataSeeder seeder = seeder(env, "59173842", companies);
            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev profile");
        }
        verifyNoInteractions(companies);
    }

    @Test
    void invalidOrMissingPinFailsWithTheDocumentedMessage() {
        CompanyRepository companies = mock(CompanyRepository.class);
        for (String pin : new String[] {null, "", "12345678", "1234"}) {
            DevDataSeeder seeder = seeder(environment("dev"), pin, companies);
            assertThatThrownBy(() -> seeder.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(DevDataSeeder.PIN_MESSAGE);
        }
        verifyNoInteractions(companies);
    }

    @Test
    void prodProfileAbortsTheSeeder() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "prod");
        CompanyRepository companies = mock(CompanyRepository.class);
        DevDataSeeder seeder = seeder(env, "59173842", companies);
        assertThatThrownBy(() -> seeder.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
        verifyNoInteractions(companies);
    }
}
