package cr.attendance.timeentry;

import com.github.benmanes.caffeine.cache.Ticker;
import cr.attendance.common.FixedWindowRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeEntryConfig {

    @Bean
    FixedWindowRateLimiter timeEntryRateLimiter(TimeEntryProperties props) {
        TimeEntryProperties.RateLimit limit = props.rateLimit();
        return new FixedWindowRateLimiter(limit.maxAttempts(), limit.window(), Ticker.systemTicker());
    }
}
