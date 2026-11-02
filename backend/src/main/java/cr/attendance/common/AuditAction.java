package cr.attendance.common;

/** What an admin did. The database only checks the format (^[A-Z_]{3,40}$), so a new value needs no migration. */
public enum AuditAction {
    PHOTO_VIEW,
    REPORT_EXPORT,
    EMPLOYEE_CREATE,
    EMPLOYEE_UPDATE,
    EMPLOYEE_ROLE_CHANGE,
    EMPLOYEE_ACTIVATE,
    EMPLOYEE_DEACTIVATE,
    PIN_RESET,
    EMPLOYEE_UNLOCK,
    BRANCH_CREATE,
    BRANCH_UPDATE,
    BRANCH_ACTIVATE,
    BRANCH_DEACTIVATE,
    SCHEDULE_UPDATE
}
