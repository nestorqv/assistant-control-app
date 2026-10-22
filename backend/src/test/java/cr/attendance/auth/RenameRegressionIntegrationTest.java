package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.JwtTestTokens;
import cr.attendance.support.TestData;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.web.servlet.MockMvc;

/** Guards against half-renamed behavior: claims, authorities, URLs and issuer after Stage 1b. */
@IntegrationTest
class RenameRegressionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JwtTestTokens tokens;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private EmployeeJwtAuthenticationConverter converter;

    @Autowired
    private ObjectMapper mapper;

    private int status(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse()
                .getStatus();
    }

    private Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "HS256").claims(c -> c.putAll(claims)).build();
    }

    @Test
    void employeeGetsRoleEmployeeAuthorityAndAdminGetsRoleAdmin() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser employee = data.user(t, "EMPLOYEE");
        TestData.TestUser admin = data.user(t, "ADMIN");

        var employeeAuth = converter.convert(jwt(Map.of("sub", String.valueOf(employee.id()),
                "companyId", t.companyId(), "role", "EMPLOYEE", "ver", 0)));
        var adminAuth = converter.convert(jwt(Map.of("sub", String.valueOf(admin.id()),
                "companyId", t.companyId(), "role", "ADMIN", "ver", 0)));

        assertThat(employeeAuth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_EMPLOYEE");
        assertThat(adminAuth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void converterIgnoresARoleClaimThatDisagreesWithTheDatabase() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser employee = data.user(t, "EMPLOYEE");
        var auth = converter.convert(jwt(Map.of("sub", String.valueOf(employee.id()),
                "companyId", t.companyId(), "role", "ADMIN", "ver", 0)));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    void converterRejectsTheLegacyEmpresaIdClaim() {
        TestData.Tenant t = data.tenant();
        TestData.TestUser employee = data.user(t, "EMPLOYEE");
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("sub", String.valueOf(employee.id()),
                "empresaId", t.companyId(), "rol", "EMPLEADO", "ver", 0))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void currentIssuerWithOnlyLegacyClaimNamesIsRejected() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtTestTokens.ISSUER)
                .subject(String.valueOf(u.id()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim("empresaId", t.companyId())
                .claim("rol", "EMPLOYEE")
                .claim("ver", 0)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        assertThat(status("/api/me", token)).isEqualTo(401);
    }

    @Test
    void legacyIssuerWithCurrentClaimNamesIsRejected() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("asistencia-backend")
                .subject(String.valueOf(u.id()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim("companyId", t.companyId())
                .claim("role", "EMPLOYEE")
                .claim("ver", 0)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        assertThat(status("/api/me", token)).isEqualTo(401);
        assertThat(status("/api/me", tokens.valid(u.id(), t.companyId(), "EMPLOYEE", 0))).isEqualTo(200);
    }

    @Test
    void removedSpanishUrlsAreNotServed() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser admin = data.user(t, "ADMIN");
        String token = data.login(t, admin);
        assertThat(status("/api/admin/employees", token)).isEqualTo(200);
        assertThat(status("/api/admin/empleados", token)).isEqualTo(404);
        MockHttpServletResponse consent = mockMvc.perform(post("/api/me/consentimiento")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(consent.getStatus()).isIn(404, 405);
    }

    @Test
    void loginResponseUsesOnlyEnglishFieldNames() throws Exception {
        TestData.Tenant t = data.tenant();
        TestData.TestUser u = data.user(t, "EMPLOYEE");
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("companyCode", t.companyCode(), "employeeCode", u.code(),
                        "pin", u.pin())))).andReturn().getResponse().getContentAsString();
        JsonNode body = mapper.readTree(json);
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("token", "tokenType", "expiresAt",
                "employee", "company");
        assertThat(body.get("employee").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "jobTitle", "role", "consentPending");
        assertThat(body.get("company").fieldNames()).toIterable().containsExactlyInAnyOrder("name", "timeZone");
        assertThat(body.get("employee").get("role").asText()).isEqualTo("EMPLOYEE");
    }
}
