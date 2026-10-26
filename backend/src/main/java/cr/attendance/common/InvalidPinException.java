package cr.attendance.common;

/** Thrown when a PIN breaks PinPolicy. The message never contains the PIN. */
public class InvalidPinException extends IllegalArgumentException {

    public InvalidPinException() {
        super("PIN does not meet the policy");
    }
}
