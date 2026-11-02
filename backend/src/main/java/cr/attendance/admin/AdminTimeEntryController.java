package cr.attendance.admin;

import cr.attendance.common.EmployeePrincipal;
import jakarta.validation.Valid;
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
 * timeentry.AdminTimeEntryPhotoController.
 */
@RestController
@RequestMapping("/api/admin/time-entries")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTimeEntryController {

    private final EntryDetailService detailService;
    private final ReviewService reviewService;

    public AdminTimeEntryController(EntryDetailService detailService, ReviewService reviewService) {
        this.detailService = detailService;
        this.reviewService = reviewService;
    }

    @GetMapping("/{id}")
    public AdminEntryDetail detail(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
        return detailService.detail(principal, id);
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
