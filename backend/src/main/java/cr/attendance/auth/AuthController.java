package cr.attendance.auth;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.TooManyAttemptsException;
import cr.attendance.employee.EmployeeRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final LoginService loginService;
    private final LoginRateLimiter rateLimiter;
    private final EmployeeRepository employeeRepository;

    public AuthController(LoginService loginService, LoginRateLimiter rateLimiter,
            EmployeeRepository employeeRepository) {
        this.loginService = loginService;
        this.rateLimiter = rateLimiter;
        this.employeeRepository = employeeRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // getRemoteAddr only: X-Forwarded-For is not trusted until the proxy is configured (Stage 5).
        String ip = http.getRemoteAddr();
        if (!rateLimiter.tryAcquire(ip)) {
            throw new TooManyAttemptsException(rateLimiter.retryAfter());
        }
        return loginService.login(request.companyCode(), request.employeeCode(), request.pin(), ip);
    }

    // Ends every session of this employee on every device (token_version bump). Ids only in the log.
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal EmployeePrincipal principal) {
        employeeRepository.incrementTokenVersion(principal.employeeId(), principal.companyId());
        log.info("Logout employee={} company={}", principal.employeeId(), principal.companyId());
    }
}
