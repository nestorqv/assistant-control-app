package cr.attendance.auth;

import cr.attendance.common.EmployeePrincipal;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class EmployeeAuthentication extends AbstractAuthenticationToken {

    private final EmployeePrincipal principal;
    private final Jwt jwt;

    public EmployeeAuthentication(EmployeePrincipal principal, Jwt jwt,
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
        return String.valueOf(principal.employeeId());
    }
}
