package cr.asistencia.auth;

import java.time.Duration;

public class DemasiadosIntentosException extends RuntimeException {

    private final Duration reintentarEn;

    public DemasiadosIntentosException(Duration reintentarEn) {
        super("Demasiados intentos");
        this.reintentarEn = reintentarEn;
    }

    public Duration getReintentarEn() {
        return reintentarEn;
    }
}
