package cr.asistencia.auth;

import cr.asistencia.common.Rol;
import cr.asistencia.empleado.Empleado;
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

    public record EmitidoToken(String token, Instant expiraEn) {
    }

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties props, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.props = props;
        this.clock = clock;
    }

    public EmitidoToken emitir(Empleado empleado) {
        Instant ahora = Instant.now(clock);
        Instant expira = ahora.plus(empleado.getRol() == Rol.ADMIN ? props.ttlAdmin() : props.ttlEmpleado());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(String.valueOf(empleado.getId()))
                .issuedAt(ahora)
                .expiresAt(expira)
                .id(UUID.randomUUID().toString())
                .claim("empresaId", empleado.getEmpresaId())
                .claim("rol", empleado.getRol().name())
                .claim("ver", empleado.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new EmitidoToken(token, expira);
    }
}
