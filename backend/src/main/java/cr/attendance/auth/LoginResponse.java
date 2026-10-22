package cr.attendance.auth;

import cr.attendance.common.Role;
import java.time.Instant;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        EmployeeInfo employee,
        CompanyInfo company) {

    public record EmployeeInfo(Long id, String name, String jobTitle, Role role, boolean consentPending) {
    }

    public record CompanyInfo(String name, String timeZone) {
    }
}
