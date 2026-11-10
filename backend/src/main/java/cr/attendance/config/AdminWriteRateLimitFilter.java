package cr.attendance.config;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.FixedWindowRateLimiter;
import cr.attendance.common.RateLimitedException;
import cr.attendance.common.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.util.UrlPathHelper;

/**
 * Per-admin limit on every write under /api/admin/ (audit finding L-4). Runs in the security chain right after
 * the bearer token is authenticated and before authorization, the dispatcher, binding and validation, so a
 * limited request writes nothing. Exactly one budget is picked by path: review actions and admin-created
 * entries use the review budget, everything else the general one, so clearing a review backlog never blocks
 * management and vice versa. Every attempt counts (also invalid ones). Only ADMIN principals are counted: an
 * employee token still gets the empty 403 from the URL rule. Not a Spring bean, like TimeEntryRateLimitFilter.
 */
public class AdminWriteRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminWriteRateLimitFilter.class);

    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    // POST /time-entries (admin-created entry) and /time-entries/{numeric id}/approve|reject|correct.
    private static final Pattern REVIEW_PATH =
            Pattern.compile("^/api/admin/time-entries(/\\d+/(approve|reject|correct))?$");

    private final FixedWindowRateLimiter generalLimiter;
    private final FixedWindowRateLimiter reviewLimiter;
    private final HandlerExceptionResolver exceptionResolver;
    private final LocaleResolver localeResolver;

    public AdminWriteRateLimitFilter(FixedWindowRateLimiter generalLimiter, FixedWindowRateLimiter reviewLimiter,
            HandlerExceptionResolver exceptionResolver, LocaleResolver localeResolver) {
        this.generalLimiter = generalLimiter;
        this.reviewLimiter = reviewLimiter;
        this.exceptionResolver = exceptionResolver;
        this.localeResolver = localeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WRITE_METHODS.contains(request.getMethod()) || !path(request).startsWith(ADMIN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EmployeePrincipal principal
                && principal.role() == Role.ADMIN) {
            boolean review = "POST".equals(request.getMethod()) && REVIEW_PATH.matcher(path(request)).matches();
            FixedWindowRateLimiter limiter = review ? reviewLimiter : generalLimiter;
            if (!limiter.tryAcquire(principal.companyId() + ":" + principal.employeeId())) {
                log.info("admin_write_rate_limited budget={} actor={} company={}", review ? "review" : "general",
                        principal.employeeId(), principal.companyId());
                // Same localized ProblemDetail (and Retry-After) as any controller-thrown 429. The language is
                // resolved here because the dispatcher, which normally sets it, has not run yet.
                LocaleContext previous = LocaleContextHolder.getLocaleContext();
                try {
                    LocaleContextHolder.setLocale(localeResolver.resolveLocale(request));
                    exceptionResolver.resolveException(request, response, null,
                            new RateLimitedException(limiter.retryAfter()));
                } finally {
                    LocaleContextHolder.setLocaleContext(previous);
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    // The decoded, container-relative path, as the dispatcher sees it: "/api/%61dmin/x" must be counted too.
    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
