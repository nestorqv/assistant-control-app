package cr.attendance.timeentry;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/time")
public class TimeController {

    private final CompanyRepository companyRepository;
    private final Clock clock;

    public TimeController(CompanyRepository companyRepository, Clock clock) {
        this.companyRepository = companyRepository;
        this.clock = clock;
    }

    @GetMapping
    public TimeResponse time(@AuthenticationPrincipal EmployeePrincipal principal) {
        Company company = companyRepository.findById(principal.companyId())
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = Instant.now(clock);
        ZoneId zone = ZoneId.of(company.getTimeZone());
        return new TimeResponse(now, now.toEpochMilli(), company.getTimeZone(),
                LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.MILLIS));
    }
}
