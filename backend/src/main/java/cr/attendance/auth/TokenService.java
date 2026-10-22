package cr.attendance.auth;

import cr.attendance.common.Role;
import cr.attendance.employee.Employee;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public record IssuedToken(String token, Instant expiresAt) {
    }

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties props, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issue(Employee employee) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(employee.getRole() == Role.ADMIN ? props.ttlAdmin() : props.ttlEmployee());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(employee.getId()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("companyId", employee.getCompanyId())
                .claim("role", employee.getRole().name())
                .claim("ver", employee.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
