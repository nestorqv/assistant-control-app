package cr.asistencia.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 20) String empresaCodigo,
        @NotBlank @Size(max = 20) String empleadoCodigo,
        @NotBlank @Size(max = 12) String pin) {

    // Never print the PIN, even by accident.
    @Override
    public String toString() {
        return "LoginRequest[empresaCodigo=" + empresaCodigo + ", empleadoCodigo=" + empleadoCodigo + "]";
    }
}
