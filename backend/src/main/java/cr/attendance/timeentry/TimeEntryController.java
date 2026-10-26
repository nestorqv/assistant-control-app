package cr.attendance.timeentry;

import cr.attendance.common.EmployeePrincipal;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/time-entries")
public class TimeEntryController {

    private final TimeEntryService service;
    private final TodayService todayService;
    private final PhotoService photoService;

    public TimeEntryController(TimeEntryService service, TodayService todayService, PhotoService photoService) {
        this.service = service;
        this.todayService = todayService;
        this.photoService = photoService;
    }

    // The per-employee rate limit runs earlier, in TimeEntryRateLimitFilter (Spring Security chain).
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TimeEntryResponse> create(@AuthenticationPrincipal EmployeePrincipal principal,
            @Valid @ModelAttribute TimeEntryForm form) {
        TimeEntryResults.Result result = service.record(principal, form);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.response());
    }

    @GetMapping("/today")
    public TodayResponse today(@AuthenticationPrincipal EmployeePrincipal principal) {
        return todayService.today(principal);
    }

    @GetMapping("/{timeEntryId}/photo")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long timeEntryId) {
        return PhotoService.toResponse(
                photoService.photoFor(principal.companyId(), principal.employeeId(), timeEntryId));
    }
}
