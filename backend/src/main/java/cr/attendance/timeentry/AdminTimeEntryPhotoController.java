package cr.attendance.timeentry;

import cr.attendance.audit.AuditService;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.FixedWindowRateLimiter;
import cr.attendance.common.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin photo view. Every attempt counts against the per-admin limiter (checked before the lookup, so a
 * scripted loop of ids is throttled even when it only gets 404s). Every successful view writes a PHOTO_VIEW
 * audit row in its own transaction; if that insert fails the photo is not served.
 */
@RestController
@RequestMapping("/api/admin/time-entries")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTimeEntryPhotoController {

    private static final Logger log = LoggerFactory.getLogger(AdminTimeEntryPhotoController.class);

    private final PhotoService photoService;
    private final AuditService auditService;
    private final FixedWindowRateLimiter limiter;

    public AdminTimeEntryPhotoController(PhotoService photoService, AuditService auditService,
            @Qualifier("photoRateLimiter") FixedWindowRateLimiter limiter) {
        this.photoService = photoService;
        this.auditService = auditService;
        this.limiter = limiter;
    }

    @GetMapping("/{timeEntryId}/photo")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long timeEntryId) {
        if (!limiter.tryAcquire(principal.companyId() + ":" + principal.employeeId())) {
            throw new TooManyAttemptsException(limiter.retryAfter());
        }
        Resource photo = photoService.photoFor(principal.companyId(), null, timeEntryId);
        auditService.record(principal.companyId(), principal.employeeId(), AuditAction.PHOTO_VIEW,
                AuditTargetType.TIME_ENTRY, timeEntryId, "entry=" + timeEntryId);
        log.info("photo_access actor={} entry={} company={}", principal.employeeId(), timeEntryId,
                principal.companyId());
        return PhotoService.toResponse(photo);
    }
}
