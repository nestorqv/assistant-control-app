package cr.attendance.admin;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.company.BranchRepository;
import cr.attendance.employee.EmployeeRepository;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Branch list and management. Every write is counted by the admin write limiter (security chain filter). */
@RestController
@RequestMapping("/api/admin/branches")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBranchController {

    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;
    private final AdminBranchService service;

    public AdminBranchController(BranchRepository branchRepository, EmployeeRepository employeeRepository,
            AdminBranchService service) {
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
        this.service = service;
    }

    @GetMapping
    public List<BranchView> list(@AuthenticationPrincipal EmployeePrincipal principal) {
        Long companyId = principal.companyId();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : employeeRepository.countActiveByBranch(companyId)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return branchRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .map(b -> BranchView.of(b, counts.getOrDefault(b.getId(), 0L)))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BranchView create(@AuthenticationPrincipal EmployeePrincipal principal,
            @Valid @RequestBody ManagementRequests.BranchCreate request) {
        return service.create(principal, request);
    }

    @PutMapping("/{id}")
    public BranchView update(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.BranchUpdate request) {
        return service.update(principal, id, request);
    }

    @PostMapping("/{id}/activate")
    public BranchView activate(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.VersionOnly request) {
        return service.activate(principal, id, request.expectedVersion());
    }

    @PostMapping("/{id}/deactivate")
    public BranchView deactivate(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id,
            @Valid @RequestBody ManagementRequests.VersionOnly request) {
        return service.deactivate(principal, id, request.expectedVersion());
    }
}
