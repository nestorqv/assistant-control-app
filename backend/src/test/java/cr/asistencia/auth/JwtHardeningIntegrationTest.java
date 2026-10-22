package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import cr.asistencia.support.IntegrationTest;
import cr.asistencia.support.TestData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Extra JWT rejection paths and "role/state come from the DB, not from the token". */
@IntegrationTest
class JwtHardeningIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestData data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtProperties jwtProperties;

    private TestData.Tenant tenant;
    private TestData.Usuario usuario;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        usuario = data.usuario(tenant, "EMPLEADO");
    }

    /** A fully valid claims builder; each test then removes or alters one claim. */
    private JwtClaimsSet.Builder base(long sub, long empresaId, String rol, int ver) {
        return JwtClaimsSet.builder()
                .issuer("asistencia-backend")
                .subject(String.valueOf(sub))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .id(UUID.randomUUID().toString())
                .claim("empresaId", empresaId)
                .claim("rol", rol)
                .claim("ver", ver);
    }

    private String firmar(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private int status(String path, String authorization) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", authorization)).andReturn().getResponse()
                .getStatus();
    }

    private int me(String token) throws Exception {
        return status("/api/me", "Bearer " + token);
    }

    private JwtClaimsSet.Builder valido() {
        return base(usuario.id(), tenant.empresaId(), "EMPLEADO", 0);
    }

    @Test
    void controlTokenBuiltByTheHelperIsAccepted() throws Exception {
        assertThat(me(firmar(valido()))).isEqualTo(200);
    }

    @Test
    void sameKeyButDifferentHmacAlgorithmIsRejected() throws Exception {
        // 48-byte key: HS384 is the strongest HMAC the key can sign. The decoder is pinned to HS256.
        JwtEncoder hs384 = new NimbusJwtEncoder(new ImmutableSecret<>(jwtProperties.secretKey(true)));
        String token = hs384.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).build(),
                valido().build())).getTokenValue();
        assertThat(me(token)).isEqualTo(401);
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String token = firmar(valido());
        String[] partes = token.split("\\.");
        char primero = partes[2].charAt(0);
        String firmaAlterada = (primero == 'A' ? 'B' : 'A') + partes[2].substring(1);
        assertThat(me(partes[0] + "." + partes[1] + "." + firmaAlterada)).isEqualTo(401);
    }

    @Test
    void tokenWithoutSignatureSegmentIsRejected() throws Exception {
        String[] partes = firmar(valido()).split("\\.");
        assertThat(me(partes[0] + "." + partes[1] + ".")).isEqualTo(401);
        assertThat(me(partes[0] + "." + partes[1])).isEqualTo(401);
    }

    @Test
    void eachRequiredClaimIsEnforced() throws Exception {
        for (String claim : new String[] {"sub", "empresaId", "rol", "ver"}) {
            JwtClaimsSet.Builder b = valido();
            b.claims(c -> c.remove(claim));
            assertThat(me(firmar(b))).as("missing " + claim).isEqualTo(401);
        }
    }

    @Test
    void tokenWithoutExpiryIsUnauthorizedEvenWhenEverythingElseIsValid() throws Exception {
        JwtClaimsSet.Builder b = valido();
        b.claims(c -> c.remove("exp"));
        assertThat(me(firmar(b))).isEqualTo(401);
    }

    private String firmarConNimbus(boolean conIat) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("asistencia-backend")
                .subject(String.valueOf(usuario.id()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .claim("empresaId", tenant.empresaId())
                .claim("rol", "EMPLEADO")
                .claim("ver", 0);
        if (conIat) {
            claims.issueTime(new java.util.Date());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(jwtProperties.secretKey(true).getEncoded()));
        return jwt.serialize();
    }

    @Test
    void tokenWithoutIssuedAtIsUnauthorized() throws Exception {
        // NimbusJwtEncoder fills in a missing iat, so these tokens are signed directly with Nimbus.
        assertThat(me(firmarConNimbus(true))).as("control with iat").isEqualTo(200);
        assertThat(me(firmarConNimbus(false))).isEqualTo(401);
    }

    @Test
    void missingIssuerIsRejected() throws Exception {
        JwtClaimsSet.Builder b = valido();
        b.claims(c -> c.remove("iss"));
        assertThat(me(firmar(b))).isEqualTo(401);
    }

    @Test
    void malformedClaimValuesAreRejectedNotServerErrors() throws Exception {
        assertThat(me(firmar(valido().subject("no-es-un-numero")))).isEqualTo(401);
        assertThat(me(firmar(valido().claim("empresaId", "abc")))).isEqualTo(401);
        assertThat(me(firmar(valido().claim("ver", "x")))).isEqualTo(401);
        assertThat(me(firmar(valido().claim("empresaId", java.util.List.of(1))))).isEqualTo(401);
        assertThat(me(firmar(valido().subject("-1")))).isEqualTo(401);
    }

    @Test
    void subjectFromAnotherEmpresaWithTheCallersEmpresaIdIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        TestData.Usuario deOtra = data.usuario(other, "EMPLEADO");
        assertThat(me(firmar(base(deOtra.id(), tenant.empresaId(), "EMPLEADO", 0)))).isEqualTo(401);
    }

    @Test
    void notBeforeInTheFutureIsRejected() throws Exception {
        assertThat(me(firmar(valido().notBefore(Instant.now().plusSeconds(600))))).isEqualTo(401);
    }

    @Test
    void expiryIsToleratedWithinTheThirtySecondSkewButNotBeyond() throws Exception {
        Instant iat = Instant.now().minusSeconds(7200);
        assertThat(me(firmar(valido().issuedAt(iat).expiresAt(Instant.now().minusSeconds(10))))).isEqualTo(200);
        assertThat(me(firmar(valido().issuedAt(iat).expiresAt(Instant.now().minusSeconds(120))))).isEqualTo(401);
    }

    @Test
    void roleComesFromTheDatabaseNotFromTheClaim() throws Exception {
        // Claim says ADMIN but the employee is an EMPLEADO: no admin access.
        String falsoAdmin = firmar(base(usuario.id(), tenant.empresaId(), "ADMIN", 0));
        assertThat(status("/api/admin/empleados", "Bearer " + falsoAdmin)).isEqualTo(403);

        // Claim says EMPLEADO but the employee is an ADMIN in the DB: admin access works.
        TestData.Usuario admin = data.usuario(tenant, "ADMIN");
        String adminConClaimDebil = firmar(base(admin.id(), tenant.empresaId(), "EMPLEADO", 0));
        assertThat(status("/api/admin/empleados", "Bearer " + adminConClaimDebil)).isEqualTo(200);
    }

    @Test
    void bumpingTokenVersionKillsOldTokensButANewLoginWorks() throws Exception {
        String viejo = data.login(tenant, usuario);
        assertThat(me(viejo)).isEqualTo(200);
        jdbc.update("update empleado set token_version = token_version + 1 where id = ?", usuario.id());
        assertThat(me(viejo)).isEqualTo(401);
        assertThat(me(data.login(tenant, usuario))).isEqualTo(200);
    }

    @Test
    void invalidatedTokensAreUnauthorizedNotForbiddenOnAdminPaths() throws Exception {
        TestData.Usuario admin = data.usuario(tenant, "ADMIN");
        String token = data.login(tenant, admin);
        assertThat(status("/api/admin/empleados", "Bearer " + token)).isEqualTo(200);

        jdbc.update("update empleado set activo = false where id = ?", admin.id());
        assertThat(status("/api/admin/empleados", "Bearer " + token)).isEqualTo(401);

        jdbc.update("update empleado set activo = true where id = ?", admin.id());
        jdbc.update("update empresa set activo = false where id = ?", tenant.empresaId());
        assertThat(status("/api/admin/empleados", "Bearer " + token)).isEqualTo(401);
    }

    @Test
    void expiredAdminTokenIsUnauthorizedNotForbidden() throws Exception {
        TestData.Usuario admin = data.usuario(tenant, "ADMIN");
        JwtClaimsSet.Builder b = base(admin.id(), tenant.empresaId(), "ADMIN", 0)
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600));
        assertThat(status("/api/admin/empleados", "Bearer " + firmar(b))).isEqualTo(401);
    }

    @Test
    void malformedAuthorizationHeadersAreUnauthorized() throws Exception {
        assertThat(status("/api/me", "Bearer not.a.jwt")).isEqualTo(401);
        assertThat(status("/api/me", "Bearer ")).isEqualTo(401);
        assertThat(status("/api/me", "Bearer")).isEqualTo(401);
        assertThat(status("/api/me", "Basic dXNlcjpwYXNz")).isEqualTo(401);
        assertThat(status("/api/me", "Bearer " + "A".repeat(5000))).isEqualTo(401);
    }

    @Test
    void tokenInTheQueryStringIsNotAccepted() throws Exception {
        String token = data.login(tenant, usuario);
        MvcResult r = mockMvc.perform(get("/api/me").param("access_token", token)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void staleTokenOnNonLoginWritesIsUnauthorized() throws Exception {
        String token = data.login(tenant, usuario);
        jdbc.update("update empleado set token_version = token_version + 1 where id = ?", usuario.id());
        MvcResult r = mockMvc.perform(post("/api/me/consentimiento").header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select consentimiento_fecha is null from empleado where id = ?",
                Boolean.class, usuario.id())).isTrue();
    }
}
