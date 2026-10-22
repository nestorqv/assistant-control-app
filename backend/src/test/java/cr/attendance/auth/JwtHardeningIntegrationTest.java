package cr.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import cr.attendance.support.IntegrationTest;
import cr.attendance.support.TestData;
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
    private TestData.TestUser user;

    @BeforeEach
    void setUp() {
        tenant = data.tenant();
        user = data.user(tenant, "EMPLOYEE");
    }

    /** A fully valid claims builder; each test then removes or alters one claim. */
    private JwtClaimsSet.Builder base(long sub, long companyId, String role, int ver) {
        return JwtClaimsSet.builder()
                .issuer("attendance-backend")
                .subject(String.valueOf(sub))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .id(UUID.randomUUID().toString())
                .claim("companyId", companyId)
                .claim("role", role)
                .claim("ver", ver);
    }

    private String sign(JwtClaimsSet.Builder claims) {
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

    private JwtClaimsSet.Builder valid() {
        return base(user.id(), tenant.companyId(), "EMPLOYEE", 0);
    }

    @Test
    void controlTokenBuiltByTheHelperIsAccepted() throws Exception {
        assertThat(me(sign(valid()))).isEqualTo(200);
    }

    @Test
    void sameKeyButDifferentHmacAlgorithmIsRejected() throws Exception {
        // 48-byte key: HS384 is the strongest HMAC the key can sign. The decoder is pinned to HS256.
        JwtEncoder hs384 = new NimbusJwtEncoder(new ImmutableSecret<>(jwtProperties.secretKey(true)));
        String token = hs384.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS384).build(),
                valid().build())).getTokenValue();
        assertThat(me(token)).isEqualTo(401);
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String token = sign(valid());
        String[] parts = token.split("\\.");
        char first = parts[2].charAt(0);
        String tamperedSignature = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThat(me(parts[0] + "." + parts[1] + "." + tamperedSignature)).isEqualTo(401);
    }

    @Test
    void tokenWithoutSignatureSegmentIsRejected() throws Exception {
        String[] parts = sign(valid()).split("\\.");
        assertThat(me(parts[0] + "." + parts[1] + ".")).isEqualTo(401);
        assertThat(me(parts[0] + "." + parts[1])).isEqualTo(401);
    }

    @Test
    void eachRequiredClaimIsEnforced() throws Exception {
        for (String claim : new String[] {"sub", "companyId", "role", "ver"}) {
            JwtClaimsSet.Builder b = valid();
            b.claims(c -> c.remove(claim));
            assertThat(me(sign(b))).as("missing " + claim).isEqualTo(401);
        }
    }

    @Test
    void tokenWithLegacyEmpresaIdClaimIsRejected() throws Exception {
        // Shape issued before the Stage 1b rename: old issuer, empresaId and rol claims.
        JwtClaimsSet.Builder legacy = JwtClaimsSet.builder()
                .issuer("asistencia-backend")
                .subject(String.valueOf(user.id()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .id(UUID.randomUUID().toString())
                .claim("empresaId", tenant.companyId())
                .claim("rol", "EMPLEADO")
                .claim("ver", 0);
        assertThat(me(sign(legacy))).isEqualTo(401);
    }

    @Test
    void tokenWithoutExpiryIsUnauthorizedEvenWhenEverythingElseIsValid() throws Exception {
        JwtClaimsSet.Builder b = valid();
        b.claims(c -> c.remove("exp"));
        assertThat(me(sign(b))).isEqualTo(401);
    }

    private String signWithNimbus(boolean withIat) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("attendance-backend")
                .subject(String.valueOf(user.id()))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .claim("companyId", tenant.companyId())
                .claim("role", "EMPLOYEE")
                .claim("ver", 0);
        if (withIat) {
            claims.issueTime(new java.util.Date());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(jwtProperties.secretKey(true).getEncoded()));
        return jwt.serialize();
    }

    @Test
    void tokenWithoutIssuedAtIsUnauthorized() throws Exception {
        // NimbusJwtEncoder fills in a missing iat, so these tokens are signed directly with Nimbus.
        assertThat(me(signWithNimbus(true))).as("control with iat").isEqualTo(200);
        assertThat(me(signWithNimbus(false))).isEqualTo(401);
    }

    @Test
    void missingIssuerIsRejected() throws Exception {
        JwtClaimsSet.Builder b = valid();
        b.claims(c -> c.remove("iss"));
        assertThat(me(sign(b))).isEqualTo(401);
    }

    @Test
    void malformedClaimValuesAreRejectedNotServerErrors() throws Exception {
        assertThat(me(sign(valid().subject("not-a-number")))).isEqualTo(401);
        assertThat(me(sign(valid().claim("companyId", "abc")))).isEqualTo(401);
        assertThat(me(sign(valid().claim("ver", "x")))).isEqualTo(401);
        assertThat(me(sign(valid().claim("companyId", java.util.List.of(1))))).isEqualTo(401);
        assertThat(me(sign(valid().subject("-1")))).isEqualTo(401);
    }

    @Test
    void subjectFromAnotherCompanyWithTheCallersCompanyIdIsRejected() throws Exception {
        TestData.Tenant other = data.tenant();
        TestData.TestUser fromOther = data.user(other, "EMPLOYEE");
        assertThat(me(sign(base(fromOther.id(), tenant.companyId(), "EMPLOYEE", 0)))).isEqualTo(401);
    }

    @Test
    void notBeforeInTheFutureIsRejected() throws Exception {
        assertThat(me(sign(valid().notBefore(Instant.now().plusSeconds(600))))).isEqualTo(401);
    }

    @Test
    void expiryIsToleratedWithinTheThirtySecondSkewButNotBeyond() throws Exception {
        Instant iat = Instant.now().minusSeconds(7200);
        assertThat(me(sign(valid().issuedAt(iat).expiresAt(Instant.now().minusSeconds(10))))).isEqualTo(200);
        assertThat(me(sign(valid().issuedAt(iat).expiresAt(Instant.now().minusSeconds(120))))).isEqualTo(401);
    }

    @Test
    void roleComesFromTheDatabaseNotFromTheClaim() throws Exception {
        // Claim says ADMIN but the employee is an EMPLOYEE: no admin access.
        String fakeAdmin = sign(base(user.id(), tenant.companyId(), "ADMIN", 0));
        assertThat(status("/api/admin/employees", "Bearer " + fakeAdmin)).isEqualTo(403);

        // Claim says EMPLOYEE but the employee is an ADMIN in the DB: admin access works.
        TestData.TestUser admin = data.user(tenant, "ADMIN");
        String adminWithWeakClaim = sign(base(admin.id(), tenant.companyId(), "EMPLOYEE", 0));
        assertThat(status("/api/admin/employees", "Bearer " + adminWithWeakClaim)).isEqualTo(200);
    }

    @Test
    void bumpingTokenVersionKillsOldTokensButANewLoginWorks() throws Exception {
        String oldToken = data.login(tenant, user);
        assertThat(me(oldToken)).isEqualTo(200);
        jdbc.update("update employee set token_version = token_version + 1 where id = ?", user.id());
        assertThat(me(oldToken)).isEqualTo(401);
        assertThat(me(data.login(tenant, user))).isEqualTo(200);
    }

    @Test
    void invalidatedTokensAreUnauthorizedNotForbiddenOnAdminPaths() throws Exception {
        TestData.TestUser admin = data.user(tenant, "ADMIN");
        String token = data.login(tenant, admin);
        assertThat(status("/api/admin/employees", "Bearer " + token)).isEqualTo(200);

        jdbc.update("update employee set active = false where id = ?", admin.id());
        assertThat(status("/api/admin/employees", "Bearer " + token)).isEqualTo(401);

        jdbc.update("update employee set active = true where id = ?", admin.id());
        jdbc.update("update company set active = false where id = ?", tenant.companyId());
        assertThat(status("/api/admin/employees", "Bearer " + token)).isEqualTo(401);
    }

    @Test
    void expiredAdminTokenIsUnauthorizedNotForbidden() throws Exception {
        TestData.TestUser admin = data.user(tenant, "ADMIN");
        JwtClaimsSet.Builder b = base(admin.id(), tenant.companyId(), "ADMIN", 0)
                .issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600));
        assertThat(status("/api/admin/employees", "Bearer " + sign(b))).isEqualTo(401);
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
        String token = data.login(tenant, user);
        MvcResult r = mockMvc.perform(get("/api/me").param("access_token", token)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void staleTokenOnNonLoginWritesIsUnauthorized() throws Exception {
        String token = data.login(tenant, user);
        jdbc.update("update employee set token_version = token_version + 1 where id = ?", user.id());
        MvcResult r = mockMvc.perform(post("/api/me/consent").header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select consent_at is null from employee where id = ?",
                Boolean.class, user.id())).isTrue();
    }
}
