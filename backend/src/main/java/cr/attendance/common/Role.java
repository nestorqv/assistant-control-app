package cr.attendance.common;

// Lives in common (not employee) so EmployeePrincipal can use it without a package cycle.
public enum Role {
    EMPLOYEE,
    ADMIN
}
