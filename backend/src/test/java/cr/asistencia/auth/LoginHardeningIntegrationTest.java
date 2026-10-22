package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Login input validation, error-body shape and cross-tenant behavior of the login endpoint. */
@IntegrationTest
class LoginHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private MvcResult postLogin(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    private MvcResult login(String empresa, String empleado, String pin) throws Exception {
        return postLogin(mapper.writeValueAsString(Map.of(
                "empresaCodigo", empresa, "empleadoCodigo", empleado, "pin", pin)));
    }

    private static String texto(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void blankAndOverlongFieldsAreBadRequest() throws Exception {
        String ok20 = "A".repeat(20);
        String ko21 = "A".repeat(21);
        assertThat(login("   ", "1001", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "   ", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "1001", "        ").getResponse().getStatus()).isEqualTo(400);
        assertThat(login(ko21, "1001", "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", ko21, "48207315").getResponse().getStatus()).isEqualTo(400);
        assertThat(login("DEMO", "1001", "1".repeat(13)).getResponse().getStatus()).isEqualTo(400);
        // Exactly at the limits: not a validation error, just a normal (failed) login.
        assertThat(login(ok20, ok20, "1".repeat(12)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void nullOrMissingFieldsAreBadRequest() throws Exception {
        assertThat(postLogin("{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(postLogin("{\"empresaCodigo\":null,\"empleadoCodigo\":null,\"pin\":null}").getResponse()
                .getStatus()).isEqualTo(400);
    }

    @Test
    void validationErrorBodyIsProblemDetailWithCodeAndNoInputEcho() throws Exception {
        MvcResult r = login("DEMO", "1001", "supersecreto-largo".repeat(2));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(texto(r)).contains("\"codigo\":\"SOLICITUD_INVALIDA\"").doesNotContain("supersecreto");
    }

    @Test
    void malformedJsonIsBadRequestProblemDetailWithCodigo() throws Exception {
        MvcResult r = postLogin("{\"empresaCodigo\": ");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(texto(r)).contains("\"codigo\":\"SOLICITUD_INVALIDA\"");
    }

    @Test
    void unsupportedMediaTypeIsProblemDetailWithCodigoAndNoInputEcho() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN)
                .content("empresaCodigo=DEMO")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(415);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(texto(r)).contains("\"codigo\":\"TIPO_CONTENIDO_NO_SOPORTADO\"")
                .doesNotContain("text/plain").doesNotContain("empresaCodigo");
    }

    @Test
    void methodNotAllowedIsProblemDetailWithCodigoAndKeepsTheAllowHeader() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        // /api/health only allows GET; an authenticated PUT reaches MVC and gets 405.
        MvcResult r = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/health").header("Authorization", "Bearer " + data.login(t, u))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(405);
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
        assertThat(r.getResponse().getHeader("Allow")).contains("GET");
        assertThat(texto(r)).contains("\"codigo\":\"METODO_NO_PERMITIDO\"").doesNotContain("PUT");
    }

    @Test
    void malformedJsonDoesNotLeakInternals() throws Exception {
        String body = texto(postLogin("{\"pin\": \"secreto-que-no-debe-salir\" oops"));
        assertThat(body).doesNotContain("secreto-que-no-debe-salir").doesNotContain("com.fasterxml")
                .doesNotContain("at cr.asistencia").doesNotContain("Exception");
    }

    @Test
    void wrongMethodAndMediaTypeDoNotLeakInternals() throws Exception {
        MvcResult nonJson = mockMvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN)
                .content("empresaCodigo=DEMO")).andReturn();
        assertThat(nonJson.getResponse().getStatus()).isEqualTo(415);
        assertThat(texto(nonJson)).doesNotContain("Exception").doesNotContain("at cr.asistencia");

        MvcResult getLogin = mockMvc.perform(get("/api/auth/login")).andReturn();
        assertThat(getLogin.getResponse().getStatus()).isIn(401, 405);
    }

    @Test
    void failureBodiesNeverContainStackTracesOrSubmittedCodes() throws Exception {
        MvcResult r = login("NOEXISTE-ZZ", "EMP-ZZ-9", "48207315");
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(texto(r)).doesNotContain("NOEXISTE-ZZ").doesNotContain("EMP-ZZ-9").doesNotContain("48207315")
                .doesNotContain("Exception").doesNotContain("at cr.asistencia").doesNotContain("trace");
        assertThat(r.getResponse().getContentType()).contains("application/problem+json");
    }

    @Test
    void employeeCodeOfAnotherEmpresaDoesNotAuthenticate() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        TestData.Usuario deB = data.usuario(b, "EMPLEADO");
        MvcResult cruzado = login(a.empresaCodigo(), deB.codigo(), deB.pin());
        assertThat(cruzado.getResponse().getStatus()).isEqualTo(401);
        MvcResult desconocido = login(a.empresaCodigo(), "NO-EXISTE", deB.pin());
        assertThat(texto(cruzado)).isEqualTo(texto(desconocido));
        // And the wrong-tenant attempt did not touch B's employee.
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                deB.id())).isZero();
    }

    @Test
    void sameEmployeeCodeInTwoEmpresasResolvesToTheRightTenant() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String hash = data.pinHash();
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            jdbc.update("""
                    insert into empleado (empresa_id, sucursal_id, codigo, nombre, pin_hash, rol)
                    values (?, ?, 'COMPARTIDO', 'X', ?, 'EMPLEADO')
                    """, t.empresaId(), t.sucursalId(), hash);
        }
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            MvcResult r = login(t.empresaCodigo(), "COMPARTIDO", TestData.PIN);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            String payload = new String(java.util.Base64.getUrlDecoder()
                    .decode(mapper.readTree(texto(r)).get("token").asText().split("\\.")[1]));
            assertThat(mapper.readTree(payload).get("empresaId").asLong()).isEqualTo(t.empresaId());
        }
    }

    @Test
    void failuresInOneEmpresaDoNotLockTheSameCodeInAnother() throws Exception {
        TestData.Tenant a = data.tenant();
        TestData.Tenant b = data.tenant();
        String hash = data.pinHash();
        for (TestData.Tenant t : new TestData.Tenant[] {a, b}) {
            jdbc.update("""
                    insert into empleado (empresa_id, sucursal_id, codigo, nombre, pin_hash, rol)
                    values (?, ?, 'COMPARTIDO', 'X', ?, 'EMPLEADO')
                    """, t.empresaId(), t.sucursalId(), hash);
        }
        for (int i = 0; i < 5; i++) {
            login(a.empresaCodigo(), "COMPARTIDO", "11112222");
        }
        assertThat(login(a.empresaCodigo(), "COMPARTIDO", TestData.PIN).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(b.empresaCodigo(), "COMPARTIDO", TestData.PIN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void inactiveAccountsDoNotCountFailures() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        jdbc.update("update empleado set activo = false where id = ?", u.id());
        for (int i = 0; i < 7; i++) {
            assertThat(login(t.empresaCodigo(), u.codigo(), "11112222").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                u.id())).isZero();
        assertThat(jdbc.queryForObject("select bloqueado_hasta is null from empleado where id = ?", Boolean.class,
                u.id())).isTrue();
    }

    @Test
    void inactiveEmpresaDoesNotCountFailuresAndGivesTheGenericBody() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        String esperado = texto(login(t.empresaCodigo(), u.codigo(), "11112222"));
        jdbc.update("update empresa set activo = false where id = ?", t.empresaId());
        MvcResult correcto = login(t.empresaCodigo(), u.codigo(), u.pin());
        MvcResult incorrecto = login(t.empresaCodigo(), u.codigo(), "11112222");
        assertThat(correcto.getResponse().getStatus()).isEqualTo(401);
        assertThat(texto(correcto)).isEqualTo(esperado);
        assertThat(texto(incorrecto)).isEqualTo(esperado);
        // Only the first (active-empresa) failure counted.
        assertThat(jdbc.queryForObject("select intentos_fallidos from empleado where id = ?", Integer.class,
                u.id())).isEqualTo(1);
    }

    @Test
    void successfulLoginDoesNotExposeLockFieldsOrHash() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        String body = texto(login(t.empresaCodigo(), u.codigo(), u.pin()));
        assertThat(body).doesNotContain("intentos").doesNotContain("bloqueado").doesNotContain("tokenVersion")
                .doesNotContain("empresaId").doesNotContain("$2");
    }
}
