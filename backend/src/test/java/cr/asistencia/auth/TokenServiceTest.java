package cr.asistencia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import cr.asistencia.common.Rol;
import cr.asistencia.empleado.Empleado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit test of the claims and TTLs, with a fixed Clock (no wall-clock dependence). */
class TokenServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-21T12:00:00Z");
    // TEST ONLY secret (same value as application-test.yml).
    private static final String SECRET =
            "dGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LXRlc3Qtb25seS1ub3QtYS1yZWFsLXNlY3JldA==";

    private JwtProperties props;
    private TokenService service;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() {
        props = new JwtProperties(SECRET, "asistencia-backend", Duration.ofHours(8), Duration.ofHours(6),
                Duration.ofSeconds(30));
        service = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(props.secretKey(true))), props,
                Clock.fixed(AHORA, ZoneOffset.UTC));
        // No timestamp validation here: the fixed clock is in the past relative to the real one.
        decoder = NimbusJwtDecoder.withSecretKey(props.secretKey(true)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(token -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
    }

    private Empleado empleado(Rol rol, int version) {
        Empleado e = new Empleado(7L, rol == Rol.ADMIN ? null : 3L, "1001", "Nombre", "Puesto", "hash", rol);
        ReflectionTestUtils.setField(e, "id", 42L);
        ReflectionTestUtils.setField(e, "tokenVersion", version);
        return e;
    }

    @Test
    void empleadoTokenExpiresInExactlyEightHours() {
        TokenService.EmitidoToken emitido = service.emitir(empleado(Rol.EMPLEADO, 0));
        Jwt jwt = decoder.decode(emitido.token());
        assertThat(jwt.getExpiresAt()).isEqualTo(AHORA.plus(Duration.ofHours(8)));
        assertThat(emitido.expiraEn()).isEqualTo(AHORA.plus(Duration.ofHours(8)));
        assertThat(jwt.getIssuedAt()).isEqualTo(AHORA);
    }

    @Test
    void adminTokenExpiresInExactlySixHours() {
        TokenService.EmitidoToken emitido = service.emitir(empleado(Rol.ADMIN, 0));
        Jwt jwt = decoder.decode(emitido.token());
        assertThat(jwt.getExpiresAt()).isEqualTo(AHORA.plus(Duration.ofHours(6)));
        assertThat(emitido.expiraEn()).isEqualTo(AHORA.plus(Duration.ofHours(6)));
    }

    @Test
    void tokenCarriesAllDocumentedClaimsAndHeaderIsHs256() {
        Jwt jwt = decoder.decode(service.emitir(empleado(Rol.EMPLEADO, 5)).token());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("asistencia-backend");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(((Number) jwt.getClaim("empresaId")).longValue()).isEqualTo(7L);
        assertThat(jwt.<String>getClaim("rol")).isEqualTo("EMPLEADO");
        assertThat(((Number) jwt.getClaim("ver")).intValue()).isEqualTo(5);
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void everyTokenHasAUniqueJti() {
        Empleado e = empleado(Rol.EMPLEADO, 0);
        String a = decoder.decode(service.emitir(e).token()).getId();
        String b = decoder.decode(service.emitir(e).token()).getId();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void tokenNeverContainsThePinHash() {
        String token = service.emitir(empleado(Rol.EMPLEADO, 0)).token();
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).doesNotContain("hash").doesNotContain("pin");
    }
}
