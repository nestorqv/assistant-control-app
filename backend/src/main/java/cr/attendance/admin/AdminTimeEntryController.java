package cr.attendance.admin;

import cr.attendance.audit.AuditService;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.FixedWindowRateLimiter;
import cr.attendance.common.RateLimitedException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evidence detail and the review actions. The photo route of the same base path lives in
 * timeentry.AdminTimeEntryPhotoController. The detail carries GPS coordinates, so (audit finding L-2) every
 * attempt counts against a per-admin limiter checked before the lookup, and every response that carries a
 * location writes a LOCATION_VIEW audit row (ids only); if that insert fails the detail is not served.
 */
@RestController
@RequestMapping("/api/admin/time-entries")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTimeEntryController {

    private static final Logger log = LoggerFactory.getLogger(AdminTimeEntryController.class);

    private final EntryDetailService detailService;
    private final ReviewService reviewService;
    private final AuditService auditService;
    private final FixedWindowRateLimiter detailLimiter;

    public AdminTimeEntryController(EntryDetailService detailService, ReviewService reviewService,
            AuditService auditService, @Qualifier("detailRateLimiter") FixedWindowRateLimiter detailLimiter) {
        this.detailService = detailService;
        this.reviewService = reviewService;
        this.auditService = auditService;
        this.detailLimiter = detailLimiter;
    }

    @GetMapping("/{id}")
    public AdminEntryDetail detail(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
        if (!detailLimiter.tryAcquire(principal.companyId() + ":" + principal.employeeId())) {
            throw new RateLimitedException(detailLimiter.retryAfter());
        }
        AdminEntryDetail detail = detailService.detail(principal, id);
        if (detail.location() != null) {
            auditService.record(principal.companyId(), principal.employeeId(), AuditAction.LOCATION_VIEW,
                    AuditTargetType.TIME_ENTRY, id, "entry=" + id);
            log.info("location_access actor={} entry={} company={}", principal.employeeId(), id,
                    principal.companyId());
        }
        return detail;
    }

    @PostMapping("/{id}/approve")
    public AdminEntryDetail approve(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ReviewRequests.Action request) {
        return reviewService.approve(principal, id, request);
    }

    @PostMapping("/{id}/reject")
    public AdminEntryDetail reject(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ReviewRequests.Action request) {
        return reviewService.reject(principal, id, request);
    }

    @PostMapping("/{id}/correct")
    public AdminEntryDetail correct(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ReviewRequests.Correct request) {
        return reviewService.correct(principal, id, request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminEntryDetail create(@AuthenticationPrincipal EmployeePrincipal principal,
            @Valid @RequestBody ReviewRequests.Create request) {
        return reviewService.create(principal, request);
    }
}
