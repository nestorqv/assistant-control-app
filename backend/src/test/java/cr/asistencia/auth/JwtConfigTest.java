package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Startup wiring of the secret check: the committed test secret only works under the test profile. */
class JwtConfigTest {

    // The value committed in application-test.yml (public).
    private static final String SECRETO_DE_PRUEBA =
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==";

    private final JwtConfig config = new JwtConfig();
    private final JwtProperties props = new JwtProperties(SECRETO_DE_PRUEBA, "asistencia-backend",
            Duration.ofHours(8), Duration.ofHours(6), Duration.ofSeconds(30));

    private static MockEnvironment perfil(String... perfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(perfiles);
        return env;
    }

    @Test
    void testSecretIsRejectedAtStartupOutsideTheTestProfile() {
        for (MockEnvironment env : new MockEnvironment[] {perfil(), perfil("dev"), perfil("prod")}) {
            assertThatThrownBy(() -> config.jwtDecoder(props, env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(SECRETO_DE_PRUEBA);
            assertThatThrownBy(() -> config.jwtEncoder(props, env)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void testSecretIsAcceptedUnderTheTestProfile() {
        assertThat(config.jwtDecoder(props, perfil("test"))).isNotNull();
        assertThat(config.jwtEncoder(props, perfil("test"))).isNotNull();
    }
}
