package cr.asistencia.auth;

import cr.asistencia.common.EmpleadoPrincipal;
import cr.asistencia.empleado.Empleado;
import cr.asistencia.empleado.EmpleadoRepository;
import cr.asistencia.empresa.Empresa;
import cr.asistencia.empresa.EmpresaRepository;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Re-checks the database on every request: the employee must exist in the token's empresa, both must
 * be active, and the token version must match. The role comes from the database, not from the claim.
 */
@Component
public class EmpleadoJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String INVALIDO = "Token inválido";

    private final EmpleadoRepository empleadoRepository;
    private final EmpresaRepository empresaRepository;

    public EmpleadoJwtAuthenticationConverter(EmpleadoRepository empleadoRepository,
            EmpresaRepository empresaRepository) {
        this.empleadoRepository = empleadoRepository;
        this.empresaRepository = empresaRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long empleadoId = aLong(jwt.getSubject());
        Long empresaId = aLong(jwt.getClaim("empresaId"));
        Long ver = aLong(jwt.getClaim("ver"));

        Empleado empleado = empleadoRepository.findByIdAndEmpresaId(empleadoId, empresaId)
                .orElseThrow(() -> new InvalidBearerTokenException(INVALIDO));
        Empresa empresa = empresaRepository.findById(empresaId)
                .orElseThrow(() -> new InvalidBearerTokenException(INVALIDO));
        if (!empleado.isActivo() || !empresa.isActivo() || empleado.getTokenVersion().longValue() != ver) {
            throw new InvalidBearerTokenException(INVALIDO);
        }

        EmpleadoPrincipal principal = new EmpleadoPrincipal(empleado.getId(), empleado.getEmpresaId(),
                empleado.getRol());
        return new EmpleadoAuthentication(principal, jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + empleado.getRol().name())));
    }

    private static Long aLong(Object value) {
        try {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text) {
                return Long.valueOf(text);
            }
        } catch (NumberFormatException e) {
            // falls through to the generic rejection below
        }
        throw new InvalidBearerTokenException(INVALIDO);
    }
}
