package cr.asistencia.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginService loginService;
    private final LoginRateLimiter rateLimiter;

    public AuthController(LoginService loginService, LoginRateLimiter rateLimiter) {
        this.loginService = loginService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // getRemoteAddr only: X-Forwarded-For is not trusted until the proxy is configured (Stage 5).
        String ip = http.getRemoteAddr();
        if (!rateLimiter.intentar(ip)) {
            throw new DemasiadosIntentosException(rateLimiter.reintentarEn());
        }
        return loginService.login(request.empresaCodigo(), request.empleadoCodigo(), request.pin(), ip);
    }
}
