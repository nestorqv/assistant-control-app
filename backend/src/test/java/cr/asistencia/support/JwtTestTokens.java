package cr.asistencia.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Builds good and deliberately bad tokens. The "other key" below is random test data. */
@Component
public class JwtTestTokens {

    public static final String ISSUER = "asistencia-backend";

    @Autowired
    private JwtEncoder realEncoder;

    private final JwtEncoder otherKeyEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(
            new SecretKeySpec("a-different-test-only-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    public String valido(long empleadoId, long empresaId, String rol, int ver) {
        return firmar(realEncoder, ISSUER, empleadoId, empresaId, rol, ver, Instant.now().plusSeconds(3600), true);
    }

    public String expirado(long empleadoId, long empresaId, String rol, int ver) {
        // Well beyond the 30 s clock skew.
        return firmar(realEncoder, ISSUER, empleadoId, empresaId, rol, ver, Instant.now().minusSeconds(600), true);
    }

    public String otroEmisor(long empleadoId, long empresaId, String rol, int ver) {
        return firmar(realEncoder, "otro-emisor", empleadoId, empresaId, rol, ver,
                Instant.now().plusSeconds(3600), true);
    }

    public String otraClave(long empleadoId, long empresaId, String rol, int ver) {
        return firmar(otherKeyEncoder, ISSUER, empleadoId, empresaId, rol, ver,
                Instant.now().plusSeconds(3600), true);
    }

    public String sinEmpresaId(long empleadoId, String rol, int ver) {
        return firmar(realEncoder, ISSUER, empleadoId, 0L, rol, ver, Instant.now().plusSeconds(3600), false);
    }

    public String algNone(long empleadoId, long empresaId, String rol, int ver) {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + empleadoId + "\",\"empresaId\":" + empresaId
                + ",\"rol\":\"" + rol + "\",\"ver\":" + ver + ",\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        return header + "." + payload + ".";
    }

    /** Replaces the payload but keeps the original signature. */
    public String conPayloadAlterado(String token, long nuevaEmpresaId) {
        String[] partes = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8);
        String alterado = payload.replaceAll("\"empresaId\":\\d+", "\"empresaId\":" + nuevaEmpresaId);
        return partes[0] + "." + b64(alterado) + "." + partes[2];
    }

    private static String firmar(JwtEncoder encoder, String issuer, long empleadoId, long empresaId, String rol,
            int ver, Instant exp, boolean conEmpresaId) {
        Instant iat = exp.isBefore(Instant.now()) ? exp.minusSeconds(3600) : Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(empleadoId))
                .issuedAt(iat)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .claim("rol", rol)
                .claim("ver", ver);
        if (conEmpresaId) {
            claims.claim("empresaId", empresaId);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
