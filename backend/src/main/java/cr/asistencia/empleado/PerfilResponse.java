package cr.asistencia.empleado;

import cr.asistencia.common.Rol;

public record PerfilResponse(
        Long id,
        String codigo,
        String nombre,
        String puesto,
        Rol rol,
        boolean consentimientoPendiente,
        EmpresaInfo empresa) {

    public record EmpresaInfo(String nombre, String zonaHoraria) {
    }
}
