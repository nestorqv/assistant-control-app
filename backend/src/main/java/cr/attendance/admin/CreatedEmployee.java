package cr.attendance.admin;

/** The one-time reveal of a new employee's PIN (201, Cache-Control: no-store). toString never prints the PIN. */
public record CreatedEmployee(AdminEmployeeView employee, String pin) {

    @Override
    public String toString() {
        return "CreatedEmployee[employee=" + employee.id() + ", pin=********]";
    }
}
