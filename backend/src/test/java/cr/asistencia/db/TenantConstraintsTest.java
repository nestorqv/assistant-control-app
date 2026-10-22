package cr.asistencia.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TenantConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Test
    void empleadoCannotUseSucursalOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        assertThatThrownBy(() -> data.empleado(a.empresaId(), b.sucursalId(), "EMPLEADO"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void horarioCannotUseEmpleadoOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long empleadoB = data.empleado(b.empresaId(), b.sucursalId(), "EMPLEADO");
        assertThatThrownBy(() -> jdbc.update("""
                insert into horario (empresa_id, empleado_id, dia_semana, hora_entrada, hora_salida)
                values (?, ?, 1, '08:00', '17:00')
                """, a.empresaId(), empleadoB)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void marcaCannotUseEmpleadoOrSucursalOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long empleadoA = data.empleado(a.empresaId(), a.sucursalId(), "EMPLEADO");
        long empleadoB = data.empleado(b.empresaId(), b.sucursalId(), "EMPLEADO");
        String sql = """
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, client_uuid)
                values (?, ?, ?, 'ENTRADA', 'MOVIL', ?)
                """;
        assertThatThrownBy(() -> jdbc.update(sql, a.empresaId(), empleadoB, a.sucursalId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, a.empresaId(), empleadoA, b.sucursalId(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evidenciaAndAjusteCannotUseMarcaOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long adminA = data.empleado(a.empresaId(), null, "ADMIN");
        long empleadoB = data.empleado(b.empresaId(), b.sucursalId(), "EMPLEADO");
        long marcaB = data.marca(b.empresaId(), empleadoB, b.sucursalId());

        assertThatThrownBy(() -> jdbc.update(
                "insert into evidencia (empresa_id, marca_id, tipo) values (?, ?, 'GPS')", a.empresaId(), marcaB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_nuevo)
                values (?, ?, ?, 'm', 'estado', 'VALIDA')
                """, a.empresaId(), marcaB, adminA)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ajusteCannotUseAdminOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long empleadoA = data.empleado(a.empresaId(), a.sucursalId(), "EMPLEADO");
        long marcaA = data.marca(a.empresaId(), empleadoA, a.sucursalId());
        long adminB = data.empleado(b.empresaId(), null, "ADMIN");
        assertThatThrownBy(() -> jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_nuevo)
                values (?, ?, ?, 'm', 'estado', 'VALIDA')
                """, a.empresaId(), marcaA, adminB)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAParentWithChildrenIsRestricted() {
        TestData.Tenant t = data.tenant();
        long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        data.marca(t.empresaId(), empleadoId, t.sucursalId());
        assertThatThrownBy(() -> jdbc.update("delete from empresa where id = ?", t.empresaId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from empleado where id = ?", empleadoId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
