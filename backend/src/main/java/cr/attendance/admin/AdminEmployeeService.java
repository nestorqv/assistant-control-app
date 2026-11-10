package cr.attendance.admin;

import cr.attendance.auth.PinGenerator;
import cr.attendance.common.ApiException;
import cr.attendance.common.Constraints;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.common.Role;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Entry point of the employee writes. Deliberately NOT transactional (the ReviewService pattern): constraint
 * violations surface at commit, after the AdminEmployeeWriter transaction has ended, so they are mapped here.
 * Lock timeouts and deadlocks are not integrity violations and pass through to 503.
 *
 * The PIN is generated and BCrypt-hashed here, before any lock is taken. It lives in a local variable only and
 * leaves this class in the one-time reveal record; it is never logged, audited or stored in plain text. A failed
 * request returns no PIN (the generated one is discarded).
 */
@Service
public class AdminEmployeeService {

    private static final Logger log = LoggerFactory.getLogger(AdminEmployeeService.class);

    private final AdminEmployeeWriter writer;
    private final EmployeeRepository employeeRepository;
    private final PinGenerator pinGenerator;
    private final PasswordEncoder passwordEncoder;

    public AdminEmployeeService(AdminEmployeeWriter writer, EmployeeRepository employeeRepository,
            PinGenerator pinGenerator, PasswordEncoder passwordEncoder) {
        this.writer = writer;
        this.employeeRepository = employeeRepository;
        this.pinGenerator = pinGenerator;
        this.passwordEncoder = passwordEncoder;
    }

    public CreatedEmployee create(EmployeePrincipal principal, ManagementRequests.EmployeeCreate request) {
        // Cheap check first: do not spend a BCrypt hash on a request the writer would refuse anyway.
        if (request.role() == Role.EMPLOYEE && request.branchId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BRANCH_REQUIRED);
        }
        String pin = pinGenerator.nextPin(request.code());
        String hash = passwordEncoder.encode(pin);
        try {
            return new CreatedEmployee(writer.create(principal, request, hash), pin);
        } catch (DataIntegrityViolationException e) {
            throw map(e);
        }
    }

    public AdminEmployeeView update(EmployeePrincipal principal, Long id, ManagementRequests.EmployeeUpdate request) {
        try {
            return writer.update(principal, id, request);
        } catch (DataIntegrityViolationException e) {
            throw map(e);
        }
    }

    public AdminEmployeeView deactivate(EmployeePrincipal principal, Long id, int expectedVersion) {
        return writer.deactivate(principal, id, expectedVersion);
    }

    public AdminEmployeeView activate(EmployeePrincipal principal, Long id, int expectedVersion) {
        return writer.activate(principal, id, expectedVersion);
    }

    public AdminEmployeeView unlock(EmployeePrincipal principal, Long id) {
        return writer.unlock(principal, id);
    }

    public PinResetResult resetPin(EmployeePrincipal principal, Long id, ManagementRequests.PinReset reason) {
        // Unlocked pre-read, only to keep the PIN from equalling the code. The writer re-validates everything
        // under its locks and does not trust this.
        // A missing row gets a plain PIN: the writer re-checks the actor first, so it alone decides 403 or 404.
        String code = employeeRepository.findByIdAndCompanyId(id, principal.companyId())
                .map(Employee::getCode).orElse(null);
        String pin = pinGenerator.nextPin(code);
        String hash = passwordEncoder.encode(pin);
        try {
            return new PinResetResult(writer.resetPin(principal, id, hash, reason), pin);
        } catch (DataIntegrityViolationException e) {
            throw map(e);
        }
    }

    private static RuntimeException map(DataIntegrityViolationException e) {
        String constraint = Constraints.nameOf(e);
        switch (constraint) {
            case "uq_employee_company_code":
                return new ApiException(HttpStatus.CONFLICT, ErrorCode.EMPLOYEE_CODE_TAKEN);
            case "ck_employee_branch_role":
                return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BRANCH_REQUIRED);
            case "fk_employee_branch_company":
                return new ResourceNotFoundException();
            case "ck_employee_name", "ck_employee_code", "ck_audit_log_reason_code", "ck_audit_log_reason_note":
                return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
            default:
                // Constraint name and class only: the driver message can carry row values (PII).
                log.error("Unexpected constraint violation while writing an employee constraint={} class={}",
                        constraint, e.getMostSpecificCause().getClass().getSimpleName());
                return e;
        }
    }
}
