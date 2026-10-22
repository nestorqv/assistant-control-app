package cr.attendance.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Ignores any Authorization header on the public endpoints (login and health), so a stale or expired
 * token cannot turn a permitAll path into a 401. Keep in sync with the permitAll rules in SecurityConfig.
 */
public class LoginAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        if (isPublicEndpoint(request.getMethod(), request.getRequestURI())) {
            return null;
        }
        return delegate.resolve(request);
    }

    private static boolean isPublicEndpoint(String method, String path) {
        return ("POST".equals(method) && "/api/auth/login".equals(path))
                || ("GET".equals(method) && ("/api/health".equals(path) || "/actuator/health".equals(path)));
    }
}
