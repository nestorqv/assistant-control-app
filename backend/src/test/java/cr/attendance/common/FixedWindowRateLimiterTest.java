package cr.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @Test
    void allowsUpToTheLimitThenBlocksPerKey() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(10), ticker);
        assertThat(limiter.tryAcquire("1:1")).isTrue();
        assertThat(limiter.tryAcquire("1:1")).isTrue();
        assertThat(limiter.tryAcquire("1:1")).isTrue();
        assertThat(limiter.tryAcquire("1:1")).isFalse();
        assertThat(limiter.tryAcquire("1:2")).isTrue();
    }

    @Test
    void theWindowExpires() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(10), ticker);
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isFalse();
        nanos.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.retryAfter()).isEqualTo(Duration.ofMinutes(10));
    }
}
