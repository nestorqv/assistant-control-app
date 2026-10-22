package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    private static JwtProperties propsWith(String secret) {
        return new JwtProperties(secret, "attendance-backend", Duration.ofHours(8), Duration.ofHours(6),
                Duration.ofSeconds(30));
    }

    private static String base64OfBytes(int n) {
        return Base64.getEncoder().encodeToString(new byte[n]);
    }

    @Test
    void blankSecretIsRejected() {
        assertThatThrownBy(() -> propsWith("").secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> propsWith(null).secretKey()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> propsWith("   ").secretKey()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonBase64SecretIsRejected() {
        assertThatThrownBy(() -> propsWith("this is not base64 !!!").secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
    }

    @Test
    void shortSecretIsRejected() {
        assertThatThrownBy(() -> propsWith(base64OfBytes(31)).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
    }

    private static String base64OfSequence(int n) {
        byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (i * 7 + 3);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    // The value committed in application-test.yml (public).
    private static final String TEST_SECRET =
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==";

    @Test
    void secretOf32VariedBytesIsAccepted() {
        assertThat(propsWith(base64OfSequence(32)).secretKey().getEncoded()).hasSize(32);
    }

    @Test
    void allZeroOrAllSameByteSecretIsRejectedAsWeak() {
        assertThatThrownBy(() -> propsWith(base64OfBytes(32)).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
        String allA = Base64.getEncoder().encodeToString("a".repeat(48).getBytes());
        assertThatThrownBy(() -> propsWith(allA).secretKey(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
    }

    @Test
    void secretWithFewDistinctBytesIsRejectedAsWeak() {
        byte[] bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 7);
        }
        String fewDistinct = Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> propsWith(fewDistinct).secretKey(true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
    }

    @Test
    void committedTestSecretIsRejectedUnlessTheTestProfileAllowsIt() {
        assertThatThrownBy(() -> propsWith(TEST_SECRET).secretKey())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("too weak");
        assertThatThrownBy(() -> propsWith(TEST_SECRET).secretKey(false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(propsWith(TEST_SECRET).secretKey(true).getEncoded()).isNotEmpty();
    }

    @Test
    void errorMessagesNeverContainTheSecret() {
        String weak = base64OfBytes(40);
        assertThatThrownBy(() -> propsWith(weak).secretKey())
                .hasMessageNotContaining(weak);
        assertThatThrownBy(() -> propsWith(TEST_SECRET).secretKey())
                .hasMessageNotContaining(TEST_SECRET).hasMessageNotContaining("test-only");
        String tooShort = base64OfBytes(10);
        assertThatThrownBy(() -> propsWith(tooShort).secretKey()).hasMessageNotContaining(tooShort);
    }
}
