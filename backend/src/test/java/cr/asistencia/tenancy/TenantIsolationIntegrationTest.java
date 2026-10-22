package cr.asistencia.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tenant isolation through the API and through the scoped repository methods. */
@IntegrationTest
class TenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmpleadoRepository empleados;

    private MvcResult getAdmin(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
    }

    @Test
    void anotherTenantsEmployeeLooksExactlyLikeAMissingOne() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario adminA = data.usuario(a, "ADMIN");
        TestData.Usuario empB = data.usuario(b, "EMPLEADO");
        String token = data.login(a, adminA);

        MvcResult otroTenant = getAdmin("/api/admin/empleados/" + empB.id(), token);
        MvcResult inexistente = getAdmin("/api/admin/empleados/" + (empB.id() + 1_000_000), token);

        assertThat(otroTenant.getResponse().getStatus()).isEqualTo(404);
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        // "instance" echoes the requested path (the caller's own input); everything else must be identical.
        String sinInstance = "\"instance\":\"[^\"]*\",?";
        String bodyOtro = otroTenant.getResponse().getContentAsString().replaceAll(sinInstance, "");
        String bodyFalta = inexistente.getResponse().getContentAsString().replaceAll(sinInstance, "");
        assertThat(bodyOtro).isEqualTo(bodyFalta).contains("\"codigo\":\"NO_ENCONTRADO\"")
                .doesNotContain(empB.codigo());
    }

    @Test
    void bothAdminsSeeOnlyTheirOwnEmployeesAndNeverEachOthers() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario adminA = data.usuario(a, "ADMIN");
        TestData.Usuario adminB = data.usuario(b, "ADMIN");
        TestData.Usuario empA = data.usuario(a, "EMPLEADO");
        TestData.Usuario empB = data.usuario(b, "EMPLEADO");

        String deA = getAdmin("/api/admin/empleados", data.login(a, adminA)).getResponse().getContentAsString();
        String deB = getAdmin("/api/admin/empleados", data.login(b, adminB)).getResponse().getContentAsString();

        assertThat(deA).contains("\"id\":" + empA.id()).doesNotContain("\"id\":" + empB.id())
                .doesNotContain("\"id\":" + adminB.id());
        assertThat(deB).contains("\"id\":" + empB.id()).doesNotContain("\"id\":" + empA.id())
                .doesNotContain("\"id\":" + adminA.id());
    }

    @Test
    void listIncludesInactiveEmployeesOfTheOwnTenantWithTheirFlag() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Usuario adminA = data.usuario(a, "ADMIN");
        TestData.Usuario baja = data.usuario(a, "EMPLEADO");
        jdbc.update("update empleado set activo = false where id = ?", baja.id());
        String json = getAdmin("/api/admin/empleados/" + baja.id(), data.login(a, adminA)).getResponse()
                .getContentAsString();
        assertThat(json).contains("\"activo\":false");
    }

    @Test
    void nonNumericOrNegativeIdsNeverLeakInternals() throws Exception {
        TestData.Tenant a = data.tenant();
        String token = data.login(a, data.usuario(a, "ADMIN"));
        MvcResult texto = getAdmin("/api/admin/empleados/abc", token);
        assertThat(texto.getResponse().getStatus()).isEqualTo(400);
        assertThat(texto.getResponse().getContentAsString()).doesNotContain("Exception")
                .doesNotContain("at cr.asistencia").doesNotContain("MethodArgumentTypeMismatch");
        assertThat(getAdmin("/api/admin/empleados/-5", token).getResponse().getStatus()).isEqualTo(404);
        assertThat(getAdmin("/api/admin/empleados/99999999999999999999", token).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void consentOfOneTenantsEmployeeNeverTouchesAnother() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario uA = data.usuario(a, "EMPLEADO");
        TestData.Usuario uB = data.usuario(b, "EMPLEADO");
        mockMvc.perform(post("/api/me/consentimiento").header("Authorization", "Bearer " + data.login(a, uA)))
                .andReturn();
        assertThat(jdbc.queryForObject("select consentimiento_fecha is not null from empleado where id = ?",
                Boolean.class, uA.id())).isTrue();
        assertThat(jdbc.queryForObject("select consentimiento_fecha is null from empleado where id = ?",
                Boolean.class, uB.id())).isTrue();
    }

    @Test
    void meReturnsTheCallersOwnEmpresaOnly() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        jdbc.update("update empresa set nombre = 'Empresa A', zona_horaria = 'America/Panama' where id = ?",
                a.empresaId());
        jdbc.update("update empresa set nombre = 'Empresa B' where id = ?", b.empresaId());
        String json = getAdmin("/api/me", data.login(a, data.usuario(a, "EMPLEADO"))).getResponse()
                .getContentAsString();
        assertThat(json).contains("Empresa A").contains("America/Panama").doesNotContain("Empresa B");
    }

    @Test
    void scopedRepositoryReadsReturnNothingForTheWrongEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario uB = data.usuario(b, "EMPLEADO");
        assertThat(empleados.findByIdAndEmpresaId(uB.id(), a.empresaId())).isEmpty();
        assertThat(empleados.findByIdAndEmpresaId(uB.id(), b.empresaId())).isPresent();
        assertThat(empleados.findByEmpresaIdAndCodigo(a.empresaId(), uB.codigo())).isEmpty();
        assertThat(empleados.findAllByEmpresaIdOrderByNombre(a.empresaId()))
                .noneMatch(e -> e.getId().equals(uB.id()));
    }

    @Test
    void scopedRepositoryWritesAffectNoRowsOfAnotherEmpresa() {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario uB = data.usuario(b, "EMPLEADO");
        Instant ahora = Instant.now();

        assertThat(empleados.registrarFallo(uB.id(), a.empresaId(), 5, ahora.plus(Duration.ofMinutes(15)), ahora))
                .isZero();
        assertThat(empleados.registrarExito(uB.id(), a.empresaId(), ahora)).isZero();
        assertThat(empleados.registrarConsentimiento(uB.id(), a.empresaId(), ahora)).isZero();

        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                uB.id())).isZero();
        assertThat(jdbc.queryForObject("select consentimiento_fecha is null from empleado where id = ?",
                Boolean.class, uB.id())).isTrue();

        // Positive control: the same calls with the right empresa do change the row.
        assertThat(empleados.registrarFallo(uB.id(), b.empresaId(), 5, ahora.plus(Duration.ofMinutes(15)), ahora))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                uB.id())).isEqualTo(1);
    }

    @Test
    void registrarFalloLeavesALockedRowUntouchedAtTheRepositoryLevel() {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        Instant ahora = Instant.now();
        jdbc.update("update empleado set bloqueado_hasta = ?, intentos_fallidos = 0 where id = ?",
                java.sql.Timestamp.from(ahora.plusSeconds(600)), u.id());
        assertThat(empleados.registrarFallo(u.id(), t.empresaId(), 5, ahora.plus(Duration.ofMinutes(15)), ahora))
                .isZero();
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                u.id())).isZero();
    }
}
