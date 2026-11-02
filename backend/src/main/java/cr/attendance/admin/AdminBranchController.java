package cr.attendance.admin;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.company.BranchRepository;
import cr.attendance.employee.EmployeeRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only in 4a: the dashboard branch filter needs the list. Create and edit arrive in 4b. */
@RestController
@RequestMapping("/api/admin/branches")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBranchController {

    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;

    public AdminBranchController(BranchRepository branchRepository, EmployeeRepository employeeRepository) {
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
    }

    public record BranchView(Long id, String name, double latitude, double longitude, int radiusMeters,
            boolean active, long activeEmployees) {
    }

    @GetMapping
    public List<BranchView> list(@AuthenticationPrincipal EmployeePrincipal principal) {
        Long companyId = principal.companyId();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : employeeRepository.countActiveByBranch(companyId)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return branchRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .map(b -> new BranchView(b.getId(), b.getName(), b.getLatitude(), b.getLongitude(),
                        b.getRadiusMeters(), b.isActive(), counts.getOrDefault(b.getId(), 0L)))
                .toList();
    }
}
