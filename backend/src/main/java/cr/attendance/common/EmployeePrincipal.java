package cr.attendance.common;

/** Authenticated caller. companyId always comes from here, never from the request. */
public record EmployeePrincipal(Long employeeId, Long companyId, Role role) {
}
