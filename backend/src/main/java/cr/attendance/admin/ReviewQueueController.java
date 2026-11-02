package cr.attendance.admin;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.timeentry.TimeEntryStatus;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/review-queue")
@PreAuthorize("hasRole('ADMIN')")
public class ReviewQueueController {

    private final EntryDetailService service;

    public ReviewQueueController(EntryDetailService service) {
        this.service = service;
    }

    @GetMapping
    public ReviewItem.Page queue(@AuthenticationPrincipal EmployeePrincipal principal,
            @RequestParam(required = false) TimeEntryStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.queue(principal, status, from, to, employeeId, page, size);
    }

    @GetMapping("/count")
    public ReviewItem.Count count(@AuthenticationPrincipal EmployeePrincipal principal) {
        return service.count(principal);
    }
}
