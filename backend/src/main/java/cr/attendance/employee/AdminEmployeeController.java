package cr.attendance.employee;

import cr.attendance.common.EmployeePrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/employees")
public class AdminEmployeeController {

    private final EmployeeQueryService service;

    public AdminEmployeeController(EmployeeQueryService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmployeeSummary> list(@AuthenticationPrincipal EmployeePrincipal principal) {
        return service.list(principal.companyId());
    }

    @GetMapping("/{id}")
    public EmployeeSummary get(@AuthenticationPrincipal EmployeePrincipal principal, @PathVariable Long id) {
        return service.get(principal.companyId(), id);
    }
}
