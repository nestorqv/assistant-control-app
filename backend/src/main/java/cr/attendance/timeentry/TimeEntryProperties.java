package cr.attendance.timeentry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.time-entry")
public record TimeEntryProperties(Duration maxShiftDuration, Duration clockSkewTolerance,
        int maxGpsAccuracyMeters, Duration lockTimeout, RateLimit rateLimit) {

    public record RateLimit(int maxAttempts, Duration window) {
    }
}
