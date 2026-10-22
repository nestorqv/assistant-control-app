package cr.asistencia.auth;

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
        assertThat(limiter.intentar("1.1.1.1")).isTrue();
        assertThat(limiter.intentar("1.1.1.1")).isTrue();
        assertThat(limiter.intentar("1.1.1.1")).isTrue();
        assertThat(limiter.intentar("1.1.1.1")).isFalse();
    }

    @Test
    void windowResetsAfterItExpires() {
        for (int i = 0; i < 4; i++) {
            limiter.intentar("1.1.1.1");
        }
        assertThat(limiter.intentar("1.1.1.1")).isFalse();
        nanos.addAndGet(Duration.ofMinutes(5).plusSeconds(1).toNanos());
        assertThat(limiter.intentar("1.1.1.1")).isTrue();
    }

    @Test
    void differentIpsAreIndependent() {
        for (int i = 0; i < 4; i++) {
            limiter.intentar("1.1.1.1");
        }
        assertThat(limiter.intentar("1.1.1.1")).isFalse();
        assertThat(limiter.intentar("2.2.2.2")).isTrue();
    }

    @Test
    void reportsTheWindowAsRetryAfter() {
        assertThat(limiter.reintentarEn()).isEqualTo(Duration.ofMinutes(5));
    }
}
