package cr.asistencia.auth;

import cr.asistencia.common.EmpleadoPrincipal;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class EmpleadoAuthentication extends AbstractAuthenticationToken {

    private final EmpleadoPrincipal principal;
    private final Jwt jwt;

    public EmpleadoAuthentication(EmpleadoPrincipal principal, Jwt jwt,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return String.valueOf(principal.empleadoId());
    }
}
