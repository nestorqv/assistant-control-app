package cr.attendance.admin;

/** The one-time reveal of a reset PIN (200, Cache-Control: no-store). toString never prints the PIN. */
public record PinResetResult(AdminEmployeeDetail employee, String pin) {

    @Override
    public String toString() {
        return "PinResetResult[employee=" + employee.employee().id() + ", pin=********]";
    }
}
