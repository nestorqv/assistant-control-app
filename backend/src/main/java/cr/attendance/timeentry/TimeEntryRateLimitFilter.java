package cr.attendance.timeentry;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.FixedWindowRateLimiter;
import cr.attendance.common.TooManyAttemptsException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Per-employee limit on POST /api/time-entries. Runs inside the Spring Security chain right after the
 * bearer-token authentication (so the employee is known) and before the dispatcher, which means before
 * content-type matching, multipart parsing, binding and validation. Every attempt counts: replays,
 * malformed bodies and wrong content types too. Not a Spring bean on purpose, so Boot does not also
 * register it as a plain servlet filter.
 */
public class TimeEntryRateLimitFilter extends OncePerRequestFilter {

    static final String PATH = "/api/time-entries";

    private final FixedWindowRateLimiter limiter;
    private final HandlerExceptionResolver exceptionResolver;
    private final LocaleResolver localeResolver;

    public TimeEntryRateLimitFilter(FixedWindowRateLimiter limiter, HandlerExceptionResolver exceptionResolver,
            LocaleResolver localeResolver) {
        this.limiter = limiter;
        this.exceptionResolver = exceptionResolver;
        this.localeResolver = localeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EmployeePrincipal principal
                && !limiter.tryAcquire(principal.companyId() + ":" + principal.employeeId())) {
            // Same localized ProblemDetail (and Retry-After) as any controller-thrown 429. The language
            // is resolved here because the dispatcher, which normally sets it, has not run yet.
            LocaleContext previous = LocaleContextHolder.getLocaleContext();
            try {
                LocaleContextHolder.setLocale(localeResolver.resolveLocale(request));
                exceptionResolver.resolveException(request, response, null,
                        new TooManyAttemptsException(limiter.retryAfter()));
            } finally {
                LocaleContextHolder.setLocaleContext(previous);
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
