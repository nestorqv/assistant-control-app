package cr.asistencia.empleado;

import cr.asistencia.common.Rol;

// Deliberately has no pin_hash and no lock fields.
public record EmpleadoResumen(
        Long id,
        String codigo,
        String nombre,
        String puesto,
        Rol rol,
        boolean activo,
        Long sucursalId) {

    static EmpleadoResumen de(Empleado e) {
        return new EmpleadoResumen(e.getId(), e.getCodigo(), e.getNombre(), e.getPuesto(), e.getRol(),
                e.isActivo(), e.getSucursalId());
    }
}
