package cr.attendance.support;

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

    public static final String ISSUER = "attendance-backend";

    @Autowired
    private JwtEncoder realEncoder;

    private final JwtEncoder otherKeyEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(
            new SecretKeySpec("a-different-test-only-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

    public String valid(long employeeId, long companyId, String role, int ver) {
        return sign(realEncoder, ISSUER, employeeId, companyId, role, ver, Instant.now().plusSeconds(3600), true);
    }

    public String expired(long employeeId, long companyId, String role, int ver) {
        // Well beyond the 30 s clock skew.
        return sign(realEncoder, ISSUER, employeeId, companyId, role, ver, Instant.now().minusSeconds(600), true);
    }

    public String otherIssuer(long employeeId, long companyId, String role, int ver) {
        return sign(realEncoder, "other-issuer", employeeId, companyId, role, ver,
                Instant.now().plusSeconds(3600), true);
    }

    public String otherKey(long employeeId, long companyId, String role, int ver) {
        return sign(otherKeyEncoder, ISSUER, employeeId, companyId, role, ver,
                Instant.now().plusSeconds(3600), true);
    }

    public String withoutCompanyId(long employeeId, String role, int ver) {
        return sign(realEncoder, ISSUER, employeeId, 0L, role, ver, Instant.now().plusSeconds(3600), false);
    }

    public String algNone(long employeeId, long companyId, String role, int ver) {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + employeeId + "\",\"companyId\":" + companyId
                + ",\"role\":\"" + role + "\",\"ver\":" + ver + ",\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}");
        return header + "." + payload + ".";
    }

    /** Replaces the payload but keeps the original signature. */
    public String withTamperedPayload(String token, long newCompanyId) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tampered = payload.replaceAll("\"companyId\":\\d+", "\"companyId\":" + newCompanyId);
        return parts[0] + "." + b64(tampered) + "." + parts[2];
    }

    private static String sign(JwtEncoder encoder, String issuer, long employeeId, long companyId, String role,
            int ver, Instant exp, boolean withCompanyId) {
        Instant iat = exp.isBefore(Instant.now()) ? exp.minusSeconds(3600) : Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(String.valueOf(employeeId))
                .issuedAt(iat)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .claim("role", role)
                .claim("ver", ver);
        if (withCompanyId) {
            claims.claim("companyId", companyId);
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
