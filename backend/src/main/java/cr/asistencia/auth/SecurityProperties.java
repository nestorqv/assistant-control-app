package cr.asistencia.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(int bcryptStrength, Lockout lockout, LoginRateLimit loginRateLimit) {

    public record Lockout(int maxIntentos, Duration duracion) {
    }

    public record LoginRateLimit(int maxIntentos, Duration ventana) {
    }
}
