package cr.asistencia.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** CHECK / NOT NULL / unique constraints of V2 that the main schema tests do not touch. */
@IntegrationTest
class SchemaRangeConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private void rechaza(Runnable accion) {
        assertThatThrownBy(accion::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sucursalCoordinatesRadiusAndNameAreChecked() {
        long empresa = data.empresa();
        String sql = "insert into sucursal (empresa_id, nombre, latitud, longitud, radio_metros) values (?, ?, ?, ?, ?)";
        jdbc.update(sql, empresa, "ok", 90, 180, 10);
        jdbc.update(sql, empresa, "ok2", -90, -180, 5000);
        rechaza(() -> jdbc.update(sql, empresa, "lat+", 90.0001, 0, 100));
        rechaza(() -> jdbc.update(sql, empresa, "lat-", -90.0001, 0, 100));
        rechaza(() -> jdbc.update(sql, empresa, "lon+", 0, 180.0001, 100));
        rechaza(() -> jdbc.update(sql, empresa, "lon-", 0, -180.0001, 100));
        rechaza(() -> jdbc.update(sql, empresa, "radio-", 0, 0, 9));
        rechaza(() -> jdbc.update(sql, empresa, "radio+", 0, 0, 5001));
        rechaza(() -> jdbc.update(sql, empresa, "ok", 1, 1, 100));
    }

    @Test
    void sameSucursalNameIsAllowedInDifferentEmpresas() {
        long a = data.empresa();
        long b = data.empresa();
        String sql = "insert into sucursal (empresa_id, nombre, latitud, longitud) values (?, 'Centro', 9, -84)";
        jdbc.update(sql, a);
        jdbc.update(sql, b);
    }

    @Test
    void empresaNameCannotBeBlankAndCodeLengthIsChecked() {
        rechaza(() -> jdbc.update("insert into empresa (codigo, nombre) values (?, '   ')", data.codigoAleatorio("N")));
        rechaza(() -> data.empresa("A".repeat(21)));
        rechaza(() -> data.empresa("CON ESPACIO"));
        rechaza(() -> data.empresa("CON_GUION_BAJO"));
        assertThat(data.empresa("ABC")).isPositive();
        assertThat(data.empresa("A-B-" + "1".repeat(16))).isPositive();
    }

    @Test
    void empleadoCounterAndPinHashConstraints() {
        TestData.Tenant t = data.tenant();
        long id = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        rechaza(() -> jdbc.update("update empleado set intentos_fallidos = -1 where id = ?", id));
        rechaza(() -> jdbc.update("update empleado set pin_hash = null where id = ?", id));
        rechaza(() -> jdbc.update("update empleado set sucursal_id = null where id = ?", id));
        rechaza(() -> jdbc.update("update empleado set codigo = '' where id = ?", id));
        rechaza(() -> jdbc.update("update empleado set codigo = ? where id = ?", "A".repeat(21), id));
        assertThat(jdbc.queryForObject("select token_version from empleado where id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void horarioHoursAndToleranceAreChecked() {
        TestData.Tenant t = data.tenant();
        long emp = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        String sql = """
                insert into horario (empresa_id, empleado_id, dia_semana, hora_entrada, hora_salida, tolerancia_min)
                values (?, ?, ?, ?::time, ?::time, ?)
                """;
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, 1, "08:00", "08:00", 0));
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, 2, "08:00", "17:00", -1));
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, 3, "08:00", "17:00", 241));
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, 0, "08:00", "17:00", 0));
        jdbc.update(sql, t.empresaId(), emp, 7, "08:00", "17:00", 240);
        // Overnight shifts are allowed by the schema (semantics are Stage 2's decision).
        jdbc.update(sql, t.empresaId(), emp, 6, "22:00", "06:00", 0);
    }

    @Test
    void marcaGeoAndDistanceChecks() {
        TestData.Tenant t = data.tenant();
        long emp = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        String sql = """
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, latitud, longitud, distancia_m, client_uuid)
                values (?, ?, ?, 'SALIDA', 'QR', ?, ?, ?, ?)
                """;
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, t.sucursalId(), 91, 0, 10, UUID.randomUUID()));
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, t.sucursalId(), 0, 181, 10, UUID.randomUUID()));
        rechaza(() -> jdbc.update(sql, t.empresaId(), emp, t.sucursalId(), 0, 0, -1, UUID.randomUUID()));
        rechaza(() -> jdbc.update(
                "insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, client_uuid) "
                        + "values (?, ?, ?, 'OTRO', 'QR', ?)", t.empresaId(), emp, t.sucursalId(), UUID.randomUUID()));
        rechaza(() -> jdbc.update(
                "insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen) "
                        + "values (?, ?, ?, 'ENTRADA', 'QR')", t.empresaId(), emp, t.sucursalId()));
        // GPS-less marks (QR/tablet) are valid: coordinates and distance are nullable.
        jdbc.update("""
                insert into marca (empresa_id, empleado_id, sucursal_id, tipo, origen, client_uuid)
                values (?, ?, ?, 'SALIDA', 'TABLET', ?)
                """, t.empresaId(), emp, t.sucursalId(), UUID.randomUUID());
    }

    @Test
    void marcaDefaultsAreApplied() {
        TestData.Tenant t = data.tenant();
        long emp = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        long id = data.marca(t.empresaId(), emp, t.sucursalId());
        assertThat(jdbc.queryForObject("select estado from marca where id = ?", String.class, id))
                .isEqualTo("VALIDA");
        assertThat(jdbc.queryForObject("select sincronizada_tarde from marca where id = ?", Boolean.class, id))
                .isFalse();
        assertThat(jdbc.queryForObject("select hora_servidor is not null from marca where id = ?", Boolean.class,
                id)).isTrue();
    }

    @Test
    void evidenciaTypeAndAjusteMotivoAreChecked() {
        TestData.Tenant t = data.tenant();
        long admin = data.empleado(t.empresaId(), null, "ADMIN");
        long emp = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        long marca = data.marca(t.empresaId(), emp, t.sucursalId());
        rechaza(() -> jdbc.update("insert into evidencia (empresa_id, marca_id, tipo) values (?, ?, 'VIDEO')",
                t.empresaId(), marca));
        // GPS evidence has no file: url_archivo is nullable.
        jdbc.update("insert into evidencia (empresa_id, marca_id, tipo) values (?, ?, 'GPS')", t.empresaId(), marca);
        rechaza(() -> jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_nuevo)
                values (?, ?, ?, '   ', 'estado', 'VALIDA')
                """, t.empresaId(), marca, admin));
        rechaza(() -> jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_nuevo)
                values (?, ?, ?, 'motivo', 'estado', null)
                """, t.empresaId(), marca, admin));
    }

    @Test
    void sameEmpleadoAndMarcaChildrenInTheSameEmpresaAreAccepted() {
        // Positive control for the composite FKs: matching empresa_id always works.
        TestData.Tenant t = data.tenant();
        long admin = data.empleado(t.empresaId(), null, "ADMIN");
        long emp = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
        long marca = data.marca(t.empresaId(), emp, t.sucursalId());
        jdbc.update("insert into evidencia (empresa_id, marca_id, tipo) values (?, ?, 'FOTO')", t.empresaId(), marca);
        jdbc.update("""
                insert into ajuste (empresa_id, marca_id, admin_id, motivo, campo, valor_anterior, valor_nuevo)
                values (?, ?, ?, 'Corrección', 'estado', 'POR_REVISAR', 'VALIDA')
                """, t.empresaId(), marca, admin);
    }

    @Test
    void sucursalOfAnotherEmpresaCannotBeReassignedToAnEmpleado() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long emp = data.empleado(a.empresaId(), a.sucursalId(), "EMPLEADO");
        rechaza(() -> jdbc.update("update empleado set sucursal_id = ? where id = ?", b.sucursalId(), emp));
    }

    @Test
    void empresaIdOfAChildCannotBeChangedToAnotherTenant() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        long emp = data.empleado(a.empresaId(), a.sucursalId(), "EMPLEADO");
        long marca = data.marca(a.empresaId(), emp, a.sucursalId());
        rechaza(() -> jdbc.update("update marca set empresa_id = ? where id = ?", b.empresaId(), marca));
    }
}
