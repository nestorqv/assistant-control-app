package cr.attendance.admin;

import cr.attendance.audit.AuditService;
import cr.attendance.common.ApiException;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.common.Role;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional part of the employee management writes (the facade is AdminEmployeeService). Locks follow the
 * AdminLocks protocol: company admin set (privilege writes only), actor FOR KEY SHARE + target FOR UPDATE in id
 * order, then the branch. The actor is re-checked under the lock in every method. Rows are changed through entity
 * mutators on rows loaded by a locking query; audit rows are written in the same transaction and hold ids, enum
 * values and field names only (never a name, a code, a PIN or a coordinate). Logs use ids only. Nothing here
 * ever sees a plain PIN: the facade hashes it before the transaction starts.
 */
@Service
public class AdminEmployeeWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminEmployeeWriter.class);

    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final AdminLocks locks;
    private final AuditService auditService;
    private final AdminEmployeeViews views;
    private final Clock clock;

    public AdminEmployeeWriter(EmployeeRepository employeeRepository, BranchRepository branchRepository,
            AdminLocks locks, AuditService auditService, AdminEmployeeViews views, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.branchRepository = branchRepository;
        this.locks = locks;
        this.auditService = auditService;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public AdminEmployeeView create(EmployeePrincipal principal, ManagementRequests.EmployeeCreate request,
            String pinHash) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        // The actor is re-read here, after the (slow) hashing the facade did before the transaction.
        locks.lockActor(companyId, principal.employeeId());
        requireBranchForEmployee(request.role(), request.branchId());
        if (request.branchId() != null) {
            Branch branch = branchRepository.findWithShareLockByIdAndCompanyId(request.branchId(), companyId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!branch.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_INACTIVE);
            }
        }
        if (employeeRepository.existsByCompanyIdAndCode(companyId, request.code())) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMPLOYEE_CODE_TAKEN);
        }
        Employee saved = employeeRepository.save(new Employee(companyId, request.branchId(), request.code(),
                request.name(), request.jobTitle(), pinHash, request.role()));
        locks.flush();
        audit(principal, AuditAction.EMPLOYEE_CREATE, saved.getId(),
                "role=" + saved.getRole() + ",branch=" + idOrNone(saved.getBranchId()), null, null);
        log.info("employee_created id={} actor={} company={}", saved.getId(), principal.employeeId(), companyId);
        return views.view(saved);
    }

    @Transactional
    public AdminEmployeeView update(EmployeePrincipal principal, Long id, ManagementRequests.EmployeeUpdate request) {
        Long companyId = principal.companyId();
        Long actorId = principal.employeeId();
        locks.applyLockTimeout();
        List<Long> admins = locks.lockAdminSet(companyId);
        Employee target = locks.lockActorAndTarget(companyId, actorId, id);
        requireVersion(target, request.expectedVersion());

        requireBranchForEmployee(request.role(), request.branchId());
        boolean branchChanges = request.branchId() != null && !request.branchId().equals(target.getBranchId());
        if (branchChanges) {
            Branch branch = branchRepository.findWithShareLockByIdAndCompanyId(request.branchId(), companyId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!branch.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_INACTIVE);
            }
        }
        boolean self = actorId.equals(id);
        if (self && request.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.SELF_CHANGE_NOT_ALLOWED);
        }
        boolean leavesAdminSet = admins.contains(id) && request.role() != Role.ADMIN;
        requireAnotherAdminRemains(admins, leavesAdminSet);
        requireActorInAdminSet(admins, actorId);

        Long oldBranchId = target.getBranchId();
        Role oldRole = target.getRole();
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(target.getName(), request.name())) {
            fields.add("name");
        }
        if (!Objects.equals(target.getJobTitle(), request.jobTitle())) {
            fields.add("jobTitle");
        }
        boolean branchChanged = !Objects.equals(oldBranchId, request.branchId());
        if (branchChanged) {
            fields.add("branchId");
        }
        boolean roleChanged = oldRole != request.role();
        if (fields.isEmpty() && !roleChanged) {
            return views.view(target);
        }
        target.updateProfile(request.name(), request.jobTitle());
        target.assignBranch(request.branchId());
        if (target.changeRole(request.role())) {
            target.endSessions();
        }
        locks.flush();
        if (roleChanged) {
            audit(principal, AuditAction.EMPLOYEE_ROLE_CHANGE, id, "role=" + oldRole + "->" + request.role(),
                    null, null);
        }
        if (!fields.isEmpty()) {
            String details = "fields=" + String.join(",", fields)
                    + (branchChanged ? ",branch=" + idOrNone(oldBranchId) + "->" + idOrNone(request.branchId()) : "");
            audit(principal, AuditAction.EMPLOYEE_UPDATE, id, details, null, null);
        }
        log.info("employee_updated id={} actor={} company={} roleChanged={}", id, actorId, companyId, roleChanged);
        return views.view(target);
    }

    @Transactional
    public AdminEmployeeView deactivate(EmployeePrincipal principal, Long id, int expectedVersion) {
        Long companyId = principal.companyId();
        Long actorId = principal.employeeId();
        locks.applyLockTimeout();
        List<Long> admins = locks.lockAdminSet(companyId);
        Employee target = locks.lockActorAndTarget(companyId, actorId, id);
        requireVersion(target, expectedVersion);

        if (actorId.equals(id)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.SELF_CHANGE_NOT_ALLOWED);
        }
        requireAnotherAdminRemains(admins, admins.contains(id));
        requireActorInAdminSet(admins, actorId);
        if (!target.isActive()) {
            return views.view(target);
        }
        target.deactivate();
        // Already rejected by the per-request active check; the bump makes sure that re-activating does not
        // revive tokens still inside their TTL.
        target.endSessions();
        locks.flush();
        audit(principal, AuditAction.EMPLOYEE_DEACTIVATE, id, null, null, null);
        log.info("employee_deactivated id={} actor={} company={}", id, actorId, companyId);
        return views.view(target);
    }

    @Transactional
    public AdminEmployeeView activate(EmployeePrincipal principal, Long id, int expectedVersion) {
        Long companyId = principal.companyId();
        Long actorId = principal.employeeId();
        locks.applyLockTimeout();
        List<Long> admins = locks.lockAdminSet(companyId);
        Employee target = locks.lockActorAndTarget(companyId, actorId, id);
        requireVersion(target, expectedVersion);
        requireActorInAdminSet(admins, actorId);
        if (target.isActive()) {
            return views.view(target);
        }
        if (target.getBranchId() != null) {
            Branch branch = branchRepository.findWithShareLockByIdAndCompanyId(target.getBranchId(), companyId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!branch.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.BRANCH_INACTIVE);
            }
        }
        target.activate();
        locks.flush();
        audit(principal, AuditAction.EMPLOYEE_ACTIVATE, id, null, null, null);
        log.info("employee_activated id={} actor={} company={}", id, actorId, companyId);
        return views.view(target);
    }

    /** pinHash is the BCrypt hash of the PIN the facade generated; the plain PIN never reaches this class. */
    @Transactional
    public AdminEmployeeDetail resetPin(EmployeePrincipal principal, Long id, String pinHash,
            ManagementRequests.PinReset reason) {
        Long companyId = principal.companyId();
        Long actorId = principal.employeeId();
        locks.applyLockTimeout();
        Employee target = locks.lockActorAndTarget(companyId, actorId, id);
        // A compromised admin session must not silently take over another working admin account. Allowed:
        // oneself, an inactive admin (prepares a re-activation) and a locked admin (the lockout recovery).
        if (!actorId.equals(id) && target.getRole() == Role.ADMIN && target.isActive()
                && !target.isLockedAt(Instant.now(clock))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ADMIN_PIN_RESET_NOT_ALLOWED);
        }
        target.resetPin(pinHash);
        locks.flush();
        audit(principal, AuditAction.PIN_RESET, id, null, reason == null || reason.reasonCode() == null ? null
                : reason.reasonCode().name(), reason == null ? null : reason.note());
        log.info("pin_reset id={} actor={} company={}", id, actorId, companyId);
        return views.detail(target);
    }

    @Transactional
    public AdminEmployeeView unlock(EmployeePrincipal principal, Long id) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        Employee target = locks.lockActorAndTarget(companyId, principal.employeeId(), id);
        if (target.unlock()) {
            locks.flush();
            audit(principal, AuditAction.EMPLOYEE_UNLOCK, id, null, null, null);
            log.info("employee_unlocked id={} actor={} company={}", id, principal.employeeId(), companyId);
        }
        return views.view(target);
    }

    private static void requireVersion(Employee target, int expectedVersion) {
        if (target.getVersion() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORD_CHANGED);
        }
    }

    private static void requireBranchForEmployee(Role role, Long branchId) {
        if (role == Role.EMPLOYEE && branchId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BRANCH_REQUIRED);
        }
    }

    // Normally unreachable (the actor is an active admin and is not the target of these writes); it is the answer
    // when the locked set would become empty anyway.
    private static void requireAnotherAdminRemains(List<Long> admins, boolean targetLeavesSet) {
        if (targetLeavesSet && admins.size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.LAST_ADMIN);
        }
    }

    // The set is locked FOR NO KEY UPDATE, so this check is exact: nobody can demote or deactivate the actor now.
    private static void requireActorInAdminSet(List<Long> admins, Long actorId) {
        if (!admins.contains(actorId)) {
            throw new AccessDeniedException("Actor is not an active administrator");
        }
    }

    private void audit(EmployeePrincipal principal, AuditAction action, Long targetId, String details,
            String reasonCode, String reasonNote) {
        auditService.recordInTransaction(principal.companyId(), principal.employeeId(), action,
                AuditTargetType.EMPLOYEE, targetId, details, reasonCode, reasonNote);
    }

    private static String idOrNone(Long id) {
        return id == null ? "none" : String.valueOf(id);
    }
}
