package cr.attendance.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Extracts the violated constraint name from a PostgreSQL integrity violation, so the facades can map it to an
 * error code and log the name instead of the driver message (which may carry row values, i.e. PII, unless the
 * driver runs with logServerErrorDetail=false). The driver's structured constraint field is read first. The primary message names the constraint either way:
 * ... violates unique constraint "uq_employee_company_code".
 */
public final class Constraints {

    private static final Pattern NAME = Pattern.compile("constraint \"([A-Za-z0-9_]+)\"");
    // Fallback: any of our constraint names (uq_, ck_, fk_ prefixes) anywhere in the message.
    private static final Pattern PREFIXED = Pattern.compile("\\b((?:uq|ck|fk)_[a-z0-9_]+)");

    private Constraints() {
    }

    /** The constraint name, or an empty string when the message does not name one. */
    public static String nameOf(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null
                    && psql.getServerErrorMessage().getConstraint() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        String message = e.getMostSpecificCause().getMessage();
        if (message == null) {
            return "";
        }
        Matcher matcher = NAME.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher prefixed = PREFIXED.matcher(message);
        return prefixed.find() ? prefixed.group(1) : "";
    }
}
