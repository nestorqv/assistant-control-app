package cr.asistencia.common;

/** Authenticated caller. empresaId always comes from here, never from the request. */
public record EmpleadoPrincipal(Long empleadoId, Long empresaId, Rol rol) {
}
