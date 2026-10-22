package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Startup wiring of the secret check: the committed test secret only works under the test profile. */
class JwtConfigTest {

    // The value committed in application-test.yml (public).
    private static final String TEST_SECRET =
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==";

    private final JwtConfig config = new JwtConfig();
    private final JwtProperties props = new JwtProperties(TEST_SECRET, "attendance-backend",
            Duration.ofHours(8), Duration.ofHours(6), Duration.ofSeconds(30));

    private static MockEnvironment profile(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void testSecretIsRejectedAtStartupOutsideTheTestProfile() {
        for (MockEnvironment env : new MockEnvironment[] {profile(), profile("dev"), profile("prod")}) {
            assertThatThrownBy(() -> config.jwtDecoder(props, env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(TEST_SECRET);
            assertThatThrownBy(() -> config.jwtEncoder(props, env)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void testSecretIsAcceptedUnderTheTestProfile() {
        assertThat(config.jwtDecoder(props, profile("test"))).isNotNull();
        assertThat(config.jwtEncoder(props, profile("test"))).isNotNull();
    }
}
