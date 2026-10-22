package cr.attendance.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(int bcryptStrength, Lockout lockout, LoginRateLimit loginRateLimit) {

    public record Lockout(int maxAttempts, Duration duration) {
    }

    public record LoginRateLimit(int maxAttempts, Duration window) {
    }
}
