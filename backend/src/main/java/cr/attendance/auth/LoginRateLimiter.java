package cr.attendance.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed window per IP. Every attempt counts, successful or not. The cache size is bounded. */
public class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Cache<String, AtomicInteger> attempts;

    public LoginRateLimiter(int maxAttempts, Duration window, Ticker ticker) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.attempts = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(window)
                .ticker(ticker)
                .build();
    }

    /** Registers an attempt; returns false when the IP is over the limit. */
    public boolean tryAcquire(String ip) {
        return attempts.get(ip, k -> new AtomicInteger()).incrementAndGet() <= maxAttempts;
    }

    public Duration retryAfter() {
        return window;
    }
}
