package cr.attendance.timeentry;

import cr.attendance.common.EmployeePrincipal;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /api/admin/** already requires the ADMIN role (SecurityConfig).
@RestController
@RequestMapping("/api/admin/time-entries")
public class AdminTimeEntryPhotoController {

    private final PhotoService photoService;

    public AdminTimeEntryPhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @GetMapping("/{timeEntryId}/photo")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal EmployeePrincipal principal,
            @PathVariable Long timeEntryId) {
        return PhotoService.toResponse(photoService.photoFor(principal.companyId(), null, timeEntryId));
    }
}
