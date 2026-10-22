package cr.asistencia.auth;

import cr.asistencia.common.Rol;
import java.time.Instant;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiraEn,
        EmpleadoInfo empleado,
        EmpresaInfo empresa) {

    public record EmpleadoInfo(Long id, String nombre, String puesto, Rol rol, boolean consentimientoPendiente) {
    }

    public record EmpresaInfo(String nombre, String zonaHoraria) {
    }
}
