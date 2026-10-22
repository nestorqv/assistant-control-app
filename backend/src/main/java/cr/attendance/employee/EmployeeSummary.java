package cr.attendance.employee;

import cr.attendance.common.Role;

// Deliberately has no pin_hash and no lock fields.
public record EmployeeSummary(
        Long id,
        String code,
        String name,
        String jobTitle,
        Role role,
        boolean active,
        Long branchId) {

    static EmployeeSummary from(Employee e) {
        return new EmployeeSummary(e.getId(), e.getCode(), e.getName(), e.getJobTitle(), e.getRole(),
                e.isActive(), e.getBranchId());
    }
}
