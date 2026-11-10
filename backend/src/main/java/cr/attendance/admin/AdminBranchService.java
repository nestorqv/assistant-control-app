package cr.attendance.admin;

import cr.attendance.common.ApiException;
import cr.attendance.common.Constraints;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Entry point of the branch writes. Deliberately NOT transactional (the ReviewService pattern): a constraint
 * violation can surface at commit, which only happens after the AdminBranchWriter transaction has ended, so it
 * can be mapped here. Lock timeouts and deadlocks are not integrity violations and pass through to 503.
 */
@Service
public class AdminBranchService {

    private static final Logger log = LoggerFactory.getLogger(AdminBranchService.class);

    private final AdminBranchWriter writer;

    public AdminBranchService(AdminBranchWriter writer) {
        this.writer = writer;
    }

    public BranchView create(EmployeePrincipal principal, ManagementRequests.BranchCreate request) {
        try {
            return writer.create(principal, request);
        } catch (DataIntegrityViolationException e) {
            throw map(e);
        }
    }

    public BranchView update(EmployeePrincipal principal, Long id, ManagementRequests.BranchUpdate request) {
        try {
            return writer.update(principal, id, request);
        } catch (DataIntegrityViolationException e) {
            throw map(e);
        }
    }

    public BranchView activate(EmployeePrincipal principal, Long id, int expectedVersion) {
        return writer.activate(principal, id, expectedVersion);
    }

    public BranchView deactivate(EmployeePrincipal principal, Long id, int expectedVersion) {
        return writer.deactivate(principal, id, expectedVersion);
    }

    private static RuntimeException map(DataIntegrityViolationException e) {
        String constraint = Constraints.nameOf(e);
        switch (constraint) {
            case "uq_branch_company_name":
                return new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_NAME_TAKEN);
            case "ck_branch_name", "ck_branch_latitude", "ck_branch_longitude", "ck_branch_radius":
                return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
            default:
                // Constraint name and class only: the driver message can carry row values (PII).
                log.error("Unexpected constraint violation while writing a branch constraint={} class={}",
                        constraint, e.getMostSpecificCause().getClass().getSimpleName());
                return e;
        }
    }
}
