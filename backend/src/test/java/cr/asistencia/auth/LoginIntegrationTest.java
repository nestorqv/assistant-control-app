package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private ResultActions login(String empresa, String empleado, String pin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "empresaCodigo", empresa, "empleadoCodigo", empleado, "pin", pin))));
    }

    @Test
    void employeeLoginReturnsTokenWithEightHourTtl() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");

        String body = login(t.empresaCodigo(), u.codigo(), u.pin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.empleado.rol").value("EMPLEADO"))
                .andExpect(jsonPath("$.empleado.consentimientoPendiente").value(true))
                .andExpect(jsonPath("$.empresa.zonaHoraria").value("America/Costa_Rica"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("pinHash").doesNotContain("$2a$");
        Instant expira = Instant.parse(mapper.readTree(body).get("expiraEn").asText());
        assertThat(Duration.between(Instant.now(), expira)).isBetween(Duration.ofHours(8).minusMinutes(1),
                Duration.ofHours(8).plusMinutes(1));
    }

    @Test
    void adminLoginHasSixHourTtl() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario admin = data.usuario(t, "ADMIN");

        String body = login(t.empresaCodigo(), admin.codigo(), admin.pin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empleado.rol").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();

        Instant expira = Instant.parse(mapper.readTree(body).get("expiraEn").asText());
        assertThat(Duration.between(Instant.now(), expira)).isBetween(Duration.ofHours(6).minusMinutes(1),
                Duration.ofHours(6).plusMinutes(1));
    }

    @Test
    void tokenCarriesTheDocumentedClaims() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        String token = data.login(t, u);

        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        JsonNode claims = mapper.readTree(payload);
        assertThat(claims.get("iss").asText()).isEqualTo("asistencia-backend");
        assertThat(claims.get("sub").asText()).isEqualTo(String.valueOf(u.id()));
        assertThat(claims.get("empresaId").asLong()).isEqualTo(t.empresaId());
        assertThat(claims.get("rol").asText()).isEqualTo("EMPLEADO");
        assertThat(claims.get("ver").asInt()).isZero();
        assertThat(claims.has("iat") && claims.has("exp") && claims.has("jti")).isTrue();
    }

    @Test
    void everyFailureHasTheSameStatusAndBody() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        TestData.Usuario inactivo = data.usuario(t, "EMPLEADO");
        jdbc.update("update empleado set activo = false where id = ?", inactivo.id());

        String wrongPin = fallo(login(t.empresaCodigo(), u.codigo(), "11112222"));
        String unknownEmpresa = fallo(login("NOEXISTE", u.codigo(), u.pin()));
        String unknownEmpleado = fallo(login(t.empresaCodigo(), "NOEXISTE", u.pin()));
        String inactive = fallo(login(t.empresaCodigo(), inactivo.codigo(), inactivo.pin()));
        String badFormat = fallo(login(t.empresaCodigo(), u.codigo(), "abc"));

        assertThat(wrongPin).contains("CREDENCIALES_INVALIDAS");
        assertThat(unknownEmpresa).isEqualTo(wrongPin);
        assertThat(unknownEmpleado).isEqualTo(wrongPin);
        assertThat(inactive).isEqualTo(wrongPin);
        assertThat(badFormat).isEqualTo(wrongPin);
    }

    @Test
    void inactiveEmpresaCannotLogIn() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        jdbc.update("update empresa set activo = false where id = ?", t.empresaId());
        String body = fallo(login(t.empresaCodigo(), u.codigo(), u.pin()));
        assertThat(body).contains("CREDENCIALES_INVALIDAS");
    }

    private String fallo(ResultActions actions) throws Exception {
        return actions.andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void lowerCaseAndPaddedCodesStillLogIn() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        login(" " + t.empresaCodigo().toLowerCase() + " ", u.codigo().toLowerCase(), u.pin())
                .andExpect(status().isOk());
    }

    @Test
    void missingFieldsGiveBadRequestWithoutEchoingInput() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empresaCodigo\":\"\",\"pin\":\"secreto-que-no-debe-salir\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secreto-que-no-debe-salir"));
    }

    @Test
    void oversizedPinIsBadRequest() throws Exception {
        login("DEMO", "1001", "9".repeat(10_000)).andExpect(status().isBadRequest());
    }

    @Test
    void staleAuthorizationHeaderOnLoginIsIgnored() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.Usuario u = data.usuario(t, "EMPLEADO");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer esto.no.es-un-token-valido")
                        .content(mapper.writeValueAsString(Map.of("empresaCodigo", t.empresaCodigo(),
                                "empleadoCodigo", u.codigo(), "pin", u.pin()))))
                .andExpect(status().isOk());
    }
}
