package cr.attendance.admin;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.FixedWindowRateLimiter;
import cr.attendance.common.RateLimitedException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee list, detail and management. Only a same-company ADMIN reaches it (URL rule + PreAuthorize); the
 * company comes from the principal. Every write is counted by the admin write limiter (security chain filter);
 * create and PIN reset also by the account limiter, checked before any PIN is generated. The two responses that
 * carry a PIN are the only place a PIN ever leaves the server: 201 / 200 with Cache-Control: no-store.
 */
@RestController
@RequestMapping("/api/admin/employees")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEmployeeController {

    private final AdminEmployeeViews views;
    private final AdminEmployeeService service;
    private final AdminScheduleWriter scheduleWriter;
    private final FixedWindowRateLimiter accountLimiter;

    public AdminEmployeeController(AdminEmployeeViews views, AdminEmployeeService service,
            AdminScheduleWriter scheduleWriter, @Qualifier("accountRateLimiter") FixedWindowRateLimiter accountLimiter) {
        this.views = views;
        this.service = service;
        this.scheduleWriter = scheduleWriter;
        this.accountLimiter = accountLimiter;
    }

    @GetMapping
    public List<AdminEmployeeView> list(@AuthenticationPrincipal EmployeePrincipal principal) {
        return views.list(principal.companyId());
    }

    @GetMapping("/{id}")
    public AdminEmployeeDetail get(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
        return views.detail(principal.companyId(), id);
    }

    @PostMapping
    public ResponseEntity<CreatedEmployee> create(@AuthenticationPrincipal EmployeePrincipal principal,
            @Valid @RequestBody ManagementRequests.EmployeeCreate request) {
        requireAccountBudget(principal);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(principal, request));
    }

    @PutMapping("/{id}")
    public AdminEmployeeView update(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.EmployeeUpdate request) {
        return service.update(principal, id, request);
    }

    @PostMapping("/{id}/deactivate")
    public AdminEmployeeView deactivate(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.VersionOnly request) {
        return service.deactivate(principal, id, request.expectedVersion());
    }

    @PostMapping("/{id}/activate")
    public AdminEmployeeView activate(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.VersionOnly request) {
        return service.activate(principal, id, request.expectedVersion());
    }

    // The body is optional: none, or {}, means "no reason".
    @PostMapping("/{id}/pin-reset")
    public ResponseEntity<PinResetResult> resetPin(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long id, @Valid @RequestBody(required = false) ManagementRequests.PinReset request) {
        requireAccountBudget(principal);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.resetPin(principal, id, request));
    }

    @PostMapping("/{id}/unlock")
    public AdminEmployeeView unlock(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
        return service.unlock(principal, id);
    }

    @GetMapping("/{id}/schedule")
    public List<ManagementRequests.ScheduleDay> schedule(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long id) {
        return scheduleWriter.get(principal.companyId(), id);
    }

    @PutMapping("/{id}/schedule")
    public List<ManagementRequests.ScheduleDay> replaceSchedule(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long id, @Valid @RequestBody ManagementRequests.ScheduleReplace request) {
        return scheduleWriter.replace(principal, id, request);
    }

    private void requireAccountBudget(EmployeePrincipal principal) {
        if (!accountLimiter.tryAcquire(principal.companyId() + ":" + principal.employeeId())) {
            throw new RateLimitedException(accountLimiter.retryAfter());
        }
    }
}
