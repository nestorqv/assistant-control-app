package cr.attendance.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/** The view plus the latest PIN reset (never the PIN: that audit row has no details). */
public record AdminEmployeeDetail(@JsonUnwrapped AdminEmployeeView employee, LastPinReset lastPinReset) {

    /** byName is the acting admin's current name; reasonCode / note are the optional ones given at the reset. */
    public record LastPinReset(Instant at, String byName, String reasonCode, String note) {
    }
}
