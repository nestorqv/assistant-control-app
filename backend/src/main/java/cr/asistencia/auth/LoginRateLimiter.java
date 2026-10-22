package cr.asistencia.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed window per IP. Every attempt counts, successful or not. The cache size is bounded. */
public class LoginRateLimiter {

    private final int maxIntentos;
    private final Duration ventana;
    private final Cache<String, AtomicInteger> intentos;

    public LoginRateLimiter(int maxIntentos, Duration ventana, Ticker ticker) {
        this.maxIntentos = maxIntentos;
        this.ventana = ventana;
        this.intentos = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(ventana)
                .ticker(ticker)
                .build();
    }

    /** Registers an attempt; returns false when the IP is over the limit. */
    public boolean intentar(String ip) {
        return intentos.get(ip, k -> new AtomicInteger()).incrementAndGet() <= maxIntentos;
    }

    public Duration reintentarEn() {
        return ventana;
    }
}
