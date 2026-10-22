package cr.attendance.employee;

import cr.attendance.common.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EmployeeQueryService {

    private final EmployeeRepository employeeRepository;

    public EmployeeQueryService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    public List<EmployeeSummary> list(Long companyId) {
        return employeeRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .map(EmployeeSummary::from)
                .toList();
    }

    // Another tenant's id is indistinguishable from a missing one: 404, never 403.
    public EmployeeSummary get(Long companyId, Long id) {
        return employeeRepository.findByIdAndCompanyId(id, companyId)
                .map(EmployeeSummary::from)
                .orElseThrow(ResourceNotFoundException::new);
    }
}
