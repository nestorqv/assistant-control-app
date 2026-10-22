package cr.asistencia.empleado;

import static org.assertj.core.api.Assertions.assertThat;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Instant;
import java.time.LocalTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A schedule hour is a wall-clock time (column type "time"), so what the entity writes must be what
 * the database stores, whatever the JVM default time zone is.
 */
@IntegrationTest
class HorarioTimeZoneIntegrationTest {

    @Autowired
    private HorarioRepository horarios;

    @Autowired
    private EmpleadoRepository empleados;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestData data;

    private String guardarYLeerComoTexto(String zonaJvm) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zonaJvm));
            TestData.Tenant t = data.tenant();
            long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
            Horario guardado = horarios.save(
                    new Horario(t.empresaId(), empleadoId, 1, LocalTime.of(6, 0), LocalTime.of(14, 30), 5));
            return jdbc.queryForObject("select hora_entrada::text || '-' || hora_salida::text from horario "
                    + "where id = ?", String.class, guardado.getId());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void controlJvmInUtcStoresTheHoursAsWritten() {
        assertThat(guardarYLeerComoTexto("UTC")).isEqualTo("06:00:00-14:30:00");
    }

    // Regression: with hibernate.jdbc.time_zone=UTC and a JVM in America/Costa_Rica (UTC-6), 06:00 used to be
    // stored as 12:00. Horario now binds LocalTime directly (see @JdbcType there).
    @Test
    void hoursAreStoredAsWrittenWhenTheJvmIsInCostaRica() {
        assertThat(guardarYLeerComoTexto("America/Costa_Rica")).isEqualTo("06:00:00-14:30:00");
    }

    @Test
    void hoursAreStoredAsWrittenInZonesAheadOfUtcToo() {
        assertThat(guardarYLeerComoTexto("Asia/Tokyo")).isEqualTo("06:00:00-14:30:00");
        assertThat(guardarYLeerComoTexto("Pacific/Kiritimati")).isEqualTo("06:00:00-14:30:00");
    }

    @Test
    void timestamptzColumnsStillStoreTheCorrectInstantWhateverTheJvmZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
            TestData.Tenant t = data.tenant();
            long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
            Instant instante = Instant.parse("2026-10-21T12:34:56Z");
            assertThat(empleados.registrarConsentimiento(empleadoId, t.empresaId(), instante)).isEqualTo(1);
            Instant guardado = jdbc.queryForObject(
                    "select consentimiento_fecha from empleado where id = ?", java.sql.Timestamp.class, empleadoId)
                    .toInstant();
            assertThat(guardado).isEqualTo(instante);
            assertThat(empleados.findByIdAndEmpresaId(empleadoId, t.empresaId()).orElseThrow()
                    .getConsentimientoFecha()).isEqualTo(instante);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void roundTripThroughTheEntityIsStableInCostaRica() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Costa_Rica"));
            TestData.Tenant t = data.tenant();
            long empleadoId = data.empleado(t.empresaId(), t.sucursalId(), "EMPLEADO");
            horarios.save(new Horario(t.empresaId(), empleadoId, 2, LocalTime.of(6, 0), LocalTime.of(14, 30), 5));
            Horario leido = horarios.findAllByEmpresaIdAndEmpleadoId(t.empresaId(), empleadoId).get(0);
            assertThat(leido.getHoraEntrada()).isEqualTo(LocalTime.of(6, 0));
            assertThat(leido.getHoraSalida()).isEqualTo(LocalTime.of(14, 30));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
