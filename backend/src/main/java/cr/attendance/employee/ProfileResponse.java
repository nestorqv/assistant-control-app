package cr.attendance.employee;

import cr.attendance.common.Role;

public record ProfileResponse(
        Long id,
        String code,
        String name,
        String jobTitle,
        Role role,
        boolean consentPending,
        CompanyInfo company) {

    public record CompanyInfo(String name, String timeZone) {
    }
}
