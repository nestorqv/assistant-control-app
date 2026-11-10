package cr.attendance.admin;

import cr.attendance.audit.AuditService;
import cr.attendance.common.ApiException;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.employee.EmployeeRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional part of the branch management writes (the facade is AdminBranchService). Lock order: the acting
 * admin (FOR KEY SHARE), then the branch (FOR UPDATE). Audit rows are written in the same transaction; they hold
 * ids and field names only, never a name or a coordinate. A no-op (nothing changed) writes no audit row.
 */
@Service
public class AdminBranchWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminBranchWriter.class);

    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;
    private final AdminLocks locks;
    private final AuditService auditService;

    public AdminBranchWriter(BranchRepository branchRepository, EmployeeRepository employeeRepository,
            AdminLocks locks, AuditService auditService) {
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
        this.locks = locks;
        this.auditService = auditService;
    }

    @Transactional
    public BranchView create(EmployeePrincipal principal, ManagementRequests.BranchCreate request) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        locks.lockActor(companyId, principal.employeeId());
        if (branchRepository.existsByCompanyIdAndNameIgnoreCase(companyId, request.name())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_NAME_TAKEN);
        }
        Branch saved = branchRepository.save(new Branch(companyId, request.name(), request.latitude(),
                request.longitude(), request.radiusMeters()));
        locks.flush();
        audit(principal, AuditAction.BRANCH_CREATE, saved.getId(), null);
        log.info("branch_created id={} actor={} company={}", saved.getId(), principal.employeeId(), companyId);
        return BranchView.of(saved, 0);
    }

    @Transactional
    public BranchView update(EmployeePrincipal principal, Long id, ManagementRequests.BranchUpdate request) {
        Long companyId = principal.companyId();
        Branch branch = lockBranch(principal, id, request.expectedVersion());
        if (!branch.getName().equals(request.name())
                && branchRepository.existsByCompanyIdAndNameIgnoreCaseAndIdNot(companyId, request.name(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_NAME_TAKEN);
        }
        List<String> changed = branch.update(request.name(), request.latitude(), request.longitude(),
                request.radiusMeters());
        if (!changed.isEmpty()) {
            locks.flush();
            audit(principal, AuditAction.BRANCH_UPDATE, id, "fields=" + String.join(",", changed));
            log.info("branch_updated id={} actor={} company={}", id, principal.employeeId(), companyId);
        }
        return view(branch);
    }

    @Transactional
    public BranchView activate(EmployeePrincipal principal, Long id, int expectedVersion) {
        Branch branch = lockBranch(principal, id, expectedVersion);
        if (branch.activate()) {
            locks.flush();
            audit(principal, AuditAction.BRANCH_ACTIVATE, id, null);
            log.info("branch_activated id={} actor={} company={}", id, principal.employeeId(), principal.companyId());
        }
        return view(branch);
    }

    @Transactional
    public BranchView deactivate(EmployeePrincipal principal, Long id, int expectedVersion) {
        Long companyId = principal.companyId();
        Branch branch = lockBranch(principal, id, expectedVersion);
        if (branch.isActive()) {
            // A new statement after the branch lock: it sees every assignment committed so far, and an assignment
            // that holds FOR SHARE first makes this wait. Inactive employees on the branch do not block.
            if (employeeRepository.countByCompanyIdAndBranchIdAndActiveTrue(companyId, id) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_IN_USE);
            }
            branch.deactivate();
            locks.flush();
            audit(principal, AuditAction.BRANCH_DEACTIVATE, id, null);
            log.info("branch_deactivated id={} actor={} company={}", id, principal.employeeId(), companyId);
        }
        return view(branch);
    }

    // Timeout, actor (re-checked: empty 403 if no longer an active admin), branch FOR UPDATE, version compare.
    private Branch lockBranch(EmployeePrincipal principal, Long id, int expectedVersion) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        locks.lockActor(companyId, principal.employeeId());
        Branch branch = branchRepository.findWithLockByIdAndCompanyId(id, companyId)
                .orElseThrow(ResourceNotFoundException::new);
        if (branch.getVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORD_CHANGED);
        }
        return branch;
    }

    private BranchView view(Branch branch) {
        return BranchView.of(branch, employeeRepository.countByCompanyIdAndBranchIdAndActiveTrue(
                branch.getCompanyId(), branch.getId()));
    }

    private void audit(EmployeePrincipal principal, AuditAction action, Long branchId, String details) {
        auditService.recordInTransaction(principal.companyId(), principal.employeeId(), action,
                AuditTargetType.BRANCH, branchId, details);
    }
}
