package cr.attendance.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String secret,
        @NotBlank String issuer,
        @NotNull Duration ttlEmployee,
        @NotNull Duration ttlAdmin,
        @NotNull Duration clockSkew) {

    static final int MIN_SECRET_BYTES = 32;
    /** A random 32-byte key has about 31 distinct byte values; fewer than this means a patterned value. */
    static final int MIN_DISTINCT_BYTES = 8;

    // Published in src/test/resources/application-test.yml, so it is public knowledge: never valid outside tests.
    private static final byte[] TEST_SECRET = Base64.getDecoder().decode(
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==");

    private static final String SECRET_MESSAGE =
            "JWT_SECRET must be base64 with at least 32 bytes (48 recommended). "
                    + "See backend/.env.example for how to generate one.";
    private static final String WEAK_SECRET_MESSAGE =
            "JWT_SECRET is too weak: it is a known value or has too little variety. "
                    + "Generate a random one as described in backend/.env.example.";

    /** Strict form: the committed test secret is rejected. */
    public SecretKey secretKey() {
        return secretKey(false);
    }

    /**
     * Fails fast (called eagerly by JwtConfig) so a missing or weak secret never reaches runtime.
     * Messages never include the secret.
     */
    public SecretKey secretKey(boolean allowTestSecret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(SECRET_MESSAGE);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(SECRET_MESSAGE);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(SECRET_MESSAGE);
        }
        if (!allowTestSecret && Arrays.equals(bytes, TEST_SECRET)) {
            throw new IllegalStateException(WEAK_SECRET_MESSAGE);
        }
        if (distinctBytes(bytes) < MIN_DISTINCT_BYTES) {
            throw new IllegalStateException(WEAK_SECRET_MESSAGE);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static int distinctBytes(byte[] bytes) {
        boolean[] seen = new boolean[256];
        int count = 0;
        for (byte b : bytes) {
            if (!seen[b & 0xFF]) {
                seen[b & 0xFF] = true;
                count++;
            }
        }
        return count;
    }
}
