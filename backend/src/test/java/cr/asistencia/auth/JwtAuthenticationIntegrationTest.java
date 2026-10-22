package cr.asistencia.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.JwtTestTokens;
import cr.asistencia.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class JwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JwtTestTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData.Tenant tenant;
    private TestData.Usuario usuario;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        usuario = data.usuario(tenant, "EMPLEADO");
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token));
    }

    private void esNoAutorizadoSinChallenge(ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void tokenFromLoginWorks() throws Exception {
        me(data.login(tenant, usuario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(usuario.id()));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void handBuiltValidTokenWorks() throws Exception {
        me(tokens.valido(usuario.id(), tenant.empresaId(), "EMPLEADO", 0)).andExpect(status().isOk());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        esNoAutorizadoSinChallenge(me(tokens.expirado(usuario.id(), tenant.empresaId(), "EMPLEADO", 0)));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        esNoAutorizadoSinChallenge(me(tokens.otroEmisor(usuario.id(), tenant.empresaId(), "EMPLEADO", 0)));
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        esNoAutorizadoSinChallenge(me(tokens.otraClave(usuario.id(), tenant.empresaId(), "EMPLEADO", 0)));
    }

    @Test
    void algNoneIsRejected() throws Exception {
        esNoAutorizadoSinChallenge(me(tokens.algNone(usuario.id(), tenant.empresaId(), "EMPLEADO", 0)));
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        String valido = tokens.valido(usuario.id(), tenant.empresaId(), "EMPLEADO", 0);
        esNoAutorizadoSinChallenge(me(tokens.conPayloadAlterado(valido, other.empresaId())));
    }

    @Test
    void missingEmpresaIdClaimIsRejected() throws Exception {
        esNoAutorizadoSinChallenge(me(tokens.sinEmpresaId(usuario.id(), "EMPLEADO", 0)));
    }

    @Test
    void employeeDeactivatedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, usuario);
        jdbc.update("update empleado set activo = false where id = ?", usuario.id());
        esNoAutorizadoSinChallenge(me(token));
    }

    @Test
    void empresaDeactivatedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, usuario);
        jdbc.update("update empresa set activo = false where id = ?", tenant.empresaId());
        esNoAutorizadoSinChallenge(me(token));
    }

    @Test
    void tokenVersionBumpedAfterIssueIsRejected() throws Exception {
        String token = data.login(tenant, usuario);
        jdbc.update("update empleado set token_version = token_version + 1 where id = ?", usuario.id());
        esNoAutorizadoSinChallenge(me(token));
    }

    @Test
    void empresaIdClaimNotMatchingTheRowIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        esNoAutorizadoSinChallenge(me(tokens.valido(usuario.id(), other.empresaId(), "EMPLEADO", 0)));
    }
}
