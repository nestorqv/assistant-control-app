package cr.attendance.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed window per key. Every attempt counts, successful or not. The cache size is bounded. */
public class FixedWindowRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Cache<String, AtomicInteger> attempts;

    public FixedWindowRateLimiter(int maxAttempts, Duration window, Ticker ticker) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.attempts = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(window)
                .ticker(ticker)
                .build();
    }

    /** Registers an attempt; returns false when the key is over the limit. */
    public boolean tryAcquire(String key) {
        return attempts.get(key, k -> new AtomicInteger()).incrementAndGet() <= maxAttempts;
    }

    public Duration retryAfter() {
        return window;
    }
}
