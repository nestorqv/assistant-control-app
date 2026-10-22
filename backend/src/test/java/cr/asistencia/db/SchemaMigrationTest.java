package cr.asistencia.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    @Test
    void v1AndV2AreAppliedSuccessfully() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null order by installed_rank",
                String.class);
        assertThat(versions).contains("1", "2");
    }

    @Test
    void sevenTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("empresa", "sucursal", "empleado", "horario", "marca", "evidencia", "ajuste");
    }

    @Test
    void zonaHorariaAndActivoHaveDefaults() {
        long id = data.empresa();
        assertThat(jdbc.queryForObject("select zona_horaria from empresa where id = ?", String.class, id))
                .isEqualTo("America/Costa_Rica");
        assertThat(jdbc.queryForObject("select activo from empresa where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void lowerCaseOrMalformedCodesAreRejected() {
        assertThatThrownBy(() -> data.empresa("demo-minusculas"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> data.empresa("AB"))
                .isInstanceOf(DataIntegrityViolationException.class);
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> jdbc.update("""
                insert into empleado (empresa_id, sucursal_id, codigo, nombre, pin_hash, rol)
                values (?, ?, 'abc', 'X', 'h', 'EMPLEADO')
                """, t.empresaId(), t.sucursalId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEmpresaCodigoIsRejected() {
        String codigo = data.codigoAleatorio("D");
        data.empresa(codigo);
        assertThatThrownBy(() -> data.empresa(codigo)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEmpleadoCodigoPerEmpresaIsRejectedButAllowedAcrossEmpresas() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String sql = """
                insert into empleado (empresa_id, sucursal_id, codigo, nombre, pin_hash, rol)
                values (?, ?, 'DUP1', 'X', 'h', 'EMPLEADO')
                """;
        jdbc.update(sql, a.empresaId(), a.sucursalId());
        jdbc.update(sql, b.empresaId(), b.sucursalId());
        assertThatThrownBy(() -> jdbc.update(sql, a.empresaId(), a.sucursalId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEnumValuesAndRangesAreRejected() {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> data.empleado(t.empresaId(), t.sucursalId(), "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");

        String marcaSql = """
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, estado, client_uuid)
                values (?, ?, ?, 'ENTRADA', ?, ?, ?)
                """;
        assertThatThrownBy(() -> jdbc.update(marcaSql, t.empresaId(), empleadoId, t.sucursalId(),
                "FAX", "VALIDA", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(marcaSql, t.empresaId(), empleadoId, t.sucursalId(),
                "MOVIL", "TAL_VEZ", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                insert into horario (empresa_id, empleado_id, dia_semana, hora_entrada, hora_salida)
                values (?, ?, 8, '08:00', '17:00')
                """, t.empresaId(), empleadoId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateClientUuidIsRejectedPerEmpresa() {
        TestData.Tenant t = data.tenant();
        long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        UUID uuid = UUID.randomUUID();
        String sql = """
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, client_uuid)
                values (?, ?, ?, 'ENTRADA', 'MOVIL', ?)
                """;
        jdbc.update(sql, t.empresaId(), empleadoId, t.sucursalId(), uuid);
        assertThatThrownBy(() -> jdbc.update(sql, t.empresaId(), empleadoId, t.sucursalId(), uuid))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The same uuid in another empresa is a different tenant's key, so it is allowed.
        TestData.Tenant other = data.tenant();
        long otherEmpleado = data.empleado(other.empresaId(), other.sucursalId(), "EMPLEADO");
        jdbc.update(sql, other.empresaId(), otherEmpleado, other.sucursalId(), uuid);
    }

    @Test
    void duplicateDiaSemanaPerEmpleadoIsRejected() {
        TestData.Tenant t = data.tenant();
        long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        String sql = """
                insert into horario (empresa_id, empleado_id, dia_semana, hora_entrada, hora_salida)
                values (?, ?, 1, '08:00', '17:00')
                """;
        jdbc.update(sql, t.empresaId(), empleadoId);
        assertThatThrownBy(() -> jdbc.update(sql, t.empresaId(), empleadoId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void empleadoWithoutSucursalIsRejectedUnlessAdmin() {
        TestData.Tenant t = data.tenant();
        assertThatThrownBy(() -> data.empleado(t.empresaId(), null, "EMPLEADO"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(data.empleado(t.empresaId(), null, "ADMIN")).isPositive();
    }

    @Test
    void ajusteAllowsNullValorAnteriorButRequiresCampoAndValorNuevo() {
        TestData.Tenant t = data.tenant();
        long adminId = data.empleado(t.empresaId(), null, "ADMIN");
        long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        long marcaId = data.marca(t.empresaId(), empleadoId, t.sucursalId());
        jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_anterior, valor_nuevo)
                values (?, ?, ?, 'Olvido marcar', 'hora_servidor', null, '2026-10-21T14:00:00Z')
                """, t.empresaId(), marcaId, adminId);
        assertThatThrownBy(() -> jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_nuevo)
                values (?, ?, ?, 'x', null, 'y')
                """, t.empresaId(), marcaId, adminId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
