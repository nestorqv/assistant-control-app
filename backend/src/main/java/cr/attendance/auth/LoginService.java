package cr.attendance.auth;

import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Not @Transactional on purpose: the failure counter must commit even though the request ends in 401.
 * Exactly one PasswordEncoder.matches runs per attempt, so every failure costs the same.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final SecurityProperties props;
    private final Clock clock;
    private final String dummyHash;

    public LoginService(CompanyRepository companyRepository, EmployeeRepository employeeRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService, SecurityProperties props, Clock clock) {
        this.companyRepository = companyRepository;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.props = props;
        this.clock = clock;
        // Same strength as real hashes, used when there is no real hash to compare against.
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String rawCompanyCode, String rawEmployeeCode, String pin, String ip) {
        String companyCode = normalize(rawCompanyCode);
        String employeeCode = normalize(rawEmployeeCode);
        Instant now = Instant.now(clock);

        Optional<Company> company = companyRepository.findByCode(companyCode);
        Optional<Employee> found = company
                .flatMap(e -> employeeRepository.findByCompanyIdAndCode(e.getId(), employeeCode));
        Employee employee = found.orElse(null);

        boolean locked = employee != null && employee.getLockedUntil() != null
                && employee.getLockedUntil().isAfter(now);
        // Unknown or locked: compare against the dummy hash. Inactive: real hash, result ignored.
        String hash = employee != null && !locked ? employee.getPinHash() : dummyHash;
        boolean pinMatches = passwordEncoder.matches(pin, hash);

        if (employee == null) {
            log.warn("Login failed: unknown company or employee, ip={}", ip);
            throw new InvalidCredentialsException();
        }
        if (locked) {
            log.warn("Login rejected: locked account, company={}, employee={}, ip={}",
                    company.get().getCode(), employee.getCode(), ip);
            throw new InvalidCredentialsException();
        }
        if (!employee.isActive() || !company.get().isActive()) {
            log.warn("Login rejected: inactive account, company={}, employee={}, ip={}",
                    company.get().getCode(), employee.getCode(), ip);
            throw new InvalidCredentialsException();
        }
        if (!pinMatches) {
            SecurityProperties.Lockout lockout = props.lockout();
            employeeRepository.recordFailedLogin(employee.getId(), employee.getCompanyId(),
                    lockout.maxAttempts(), now.plus(lockout.duration()), now);
            log.warn("Login failed: wrong PIN, company={}, employee={}, ip={}",
                    company.get().getCode(), employee.getCode(), ip);
            throw new InvalidCredentialsException();
        }

        // The lock was checked before the slow BCrypt compare. This conditional UPDATE re-checks it
        // atomically, so a correct guess that raced a concurrent lock gets no token and clears nothing.
        if (employeeRepository.recordSuccessfulLogin(employee.getId(), employee.getCompanyId(), now) == 0) {
            log.warn("Login rejected: account locked during the attempt, company={}, employee={}, ip={}",
                    company.get().getCode(), employee.getCode(), ip);
            throw new InvalidCredentialsException();
        }
        TokenService.IssuedToken issued = tokenService.issue(employee);
        return new LoginResponse(
                issued.token(),
                "Bearer",
                issued.expiresAt(),
                new LoginResponse.EmployeeInfo(employee.getId(), employee.getName(), employee.getJobTitle(),
                        employee.getRole(), employee.getConsentAt() == null),
                new LoginResponse.CompanyInfo(company.get().getName(), company.get().getTimeZone()));
    }

    private static String normalize(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
