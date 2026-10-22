package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5), ticker);

    @Test
    void allowsUpToTheLimitThenBlocks() {
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
    }

    @Test
    void windowResetsAfterItExpires() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("1.1.1.1");
        }
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        nanos.addAndGet(Duration.ofMinutes(5).plusSeconds(1).toNanos());
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test
    void differentIpsAreIndependent() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("1.1.1.1");
        }
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
    }

    @Test
    void reportsTheWindowAsRetryAfter() {
        assertThat(limiter.retryAfter()).isEqualTo(Duration.ofMinutes(5));
    }
}
