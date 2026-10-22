package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static JwtProperties con(String secret) {
        return new JwtProperties(secret, "asistencia-backend", Duration.ofHours(8), Duration.ofHours(6),
                Duration.ofSeconds(30));
    }

    private static String base64DeBytes(int n) {
        return Base64.getEncoder().encodeToString(new byte[n]);
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> con("").secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> con(null).secretKey()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> con("   ").secretKey()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonBase64SecretIsRejected() {
        assertThatThrownBy(() -> con("esto no es base64 !!!").secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> con(base64DeBytes(31)).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
    }

    private static String base64DeSecuencia(int n) {
        byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (i * 7 + 3);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    // The value committed in application-test.yml (public).
    private static final String SECRETO_DE_PRUEBA =
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==";

    @Test
    void secretOf32VariedBytesIsAccepted() {
        assertThat(con(base64DeSecuencia(32)).secretKey().getEncoded()).hasSize(32);
    }

    @Test
    void allZeroOrAllSameByteSecretIsRejectedAsWeak() {
        assertThatThrownBy(() -> con(base64DeBytes(32)).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
        String todasA = Base64.getEncoder().encodeToString("a".repeat(48).getBytes());
        assertThatThrownBy(() -> con(todasA).secretKey(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
    }

    @Test
    void secretWithFewDistinctBytesIsRejectedAsWeak() {
        byte[] bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 7);
        }
        String poco = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> con(poco).secretKey(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
    }

    @Test
    void committedTestSecretIsRejectedUnlessTheTestProfileAllowsIt() {
        assertThatThrownBy(() -> con(SECRETO_DE_PRUEBA).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
        assertThatThrownBy(() -> con(SECRETO_DE_PRUEBA).secretKey(false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(con(SECRETO_DE_PRUEBA).secretKey(true).getEncoded()).isNotEmpty();
    }

    @Test
    void errorMessagesNeverContainTheSecret() {
        String debil = base64DeBytes(40);
        assertThatThrownBy(() -> con(debil).secretKey())
                .hasMessageNotContaining(debil);
        assertThatThrownBy(() -> con(SECRETO_DE_PRUEBA).secretKey())
                .hasMessageNotContaining(SECRETO_DE_PRUEBA).hasMessageNotContaining("test-only");
        String corto = base64DeBytes(10);
        assertThatThrownBy(() -> con(corto).secretKey()).hasMessageNotContaining(corto);
    }
}
