package cr.attendance.config;

import com.github.benmanes.caffeine.cache.Ticker;
import cr.attendance.admin.AdminProperties;
import cr.attendance.common.FixedWindowRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Per-admin limiters (key companyId:employeeId). Beans, so the timeentry package gets its limiter injected
 * by name and never imports the admin package.
 */
@Configuration
public class AdminRateLimits {

    @Bean
    FixedWindowRateLimiter photoRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().photo());
    }

    @Bean
    FixedWindowRateLimiter exportRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().export());
    }

    @Bean
    FixedWindowRateLimiter accountRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().account());
    }

    @Bean
    FixedWindowRateLimiter adminWriteRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().write());
    }

    @Bean
    FixedWindowRateLimiter reviewWriteRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().reviewWrite());
    }

    @Bean
    FixedWindowRateLimiter detailRateLimiter(AdminProperties props) {
        return limiter(props.rateLimit().detail());
    }

    private static FixedWindowRateLimiter limiter(AdminProperties.Limit limit) {
        return new FixedWindowRateLimiter(limit.maxAttempts(), limit.window(), Ticker.systemTicker());
    }
}
