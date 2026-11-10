package cr.attendance.admin;

import cr.attendance.common.Role;
import cr.attendance.company.Branch;
import cr.attendance.employee.Employee;
import java.time.Instant;

/**
 * What the admin panel sees of an employee. Deliberately has no pin_hash, failed_attempts or token_version.
 * lockedUntil is only set while the account is locked. Visible to a same-company ADMIN only (URL rule).
 */
public record AdminEmployeeView(
        Long id,
        String code,
        String name,
        String jobTitle,
        Role role,
        boolean active,
        Long branchId,
        String branchName,
        Boolean branchActive,
        boolean consentGiven,
        boolean locked,
        Instant lockedUntil,
        boolean hasSchedule,
        int version) {

    static AdminEmployeeView of(Employee e, Branch branch, boolean hasSchedule, Instant now) {
        boolean locked = e.isLockedAt(now);
        return new AdminEmployeeView(e.getId(), e.getCode(), e.getName(), e.getJobTitle(), e.getRole(),
                e.isActive(), e.getBranchId(), branch == null ? null : branch.getName(),
                branch == null ? null : branch.isActive(), e.getConsentAt() != null, locked,
                locked ? e.getLockedUntil() : null, hasSchedule, e.getVersion());
    }
}
