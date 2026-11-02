package cr.attendance.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.admin")
public record AdminProperties(int maxDashboardRows, RateLimits rateLimit) {

    public record Limit(int maxAttempts, Duration window) {
    }

    /** Per admin: photo views, Excel exports, and PIN reset plus employee creation. */
    public record RateLimits(Limit photo, Limit export, Limit account) {
    }
}
