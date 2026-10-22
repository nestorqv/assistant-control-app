package cr.attendance.employee;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final Clock clock;

    public MeController(EmployeeRepository employeeRepository, CompanyRepository companyRepository, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.clock = clock;
    }

    @GetMapping
    public ProfileResponse profile(@AuthenticationPrincipal EmployeePrincipal principal) {
        Employee employee = employeeRepository.findByIdAndCompanyId(principal.employeeId(), principal.companyId())
                .orElseThrow(ResourceNotFoundException::new);
        Company company = companyRepository.findById(principal.companyId())
                .orElseThrow(ResourceNotFoundException::new);
        return new ProfileResponse(employee.getId(), employee.getCode(), employee.getName(),
                employee.getJobTitle(), employee.getRole(), employee.getConsentAt() == null,
                new ProfileResponse.CompanyInfo(company.getName(), company.getTimeZone()));
    }

    // Idempotent: only the first call sets the date (Ley 8968 consent).
    @PostMapping("/consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void giveConsent(@AuthenticationPrincipal EmployeePrincipal principal) {
        employeeRepository.recordConsent(principal.employeeId(), principal.companyId(),
                Instant.now(clock));
    }
}
