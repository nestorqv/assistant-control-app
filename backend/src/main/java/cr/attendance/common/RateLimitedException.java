package cr.attendance.common;

import java.time.Duration;

/** A per-admin limiter said no. Answered as 429 RATE_LIMITED with Retry-After. */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super("Rate limited");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
