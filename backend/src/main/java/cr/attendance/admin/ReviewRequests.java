package cr.attendance.admin;

import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request bodies of the review actions. Explicit records: nothing is bound to an entity and unknown properties
 * are ignored. The reason is trimmed first; control characters (tabs and line breaks too) are rejected.
 */
public final class ReviewRequests {

    static final String NO_CONTROL_CHARACTERS = "^[^\\p{Cc}]*$";
    static final String LOCAL_DATE_TIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$";

    private ReviewRequests() {
    }

    static String trim(String s) {
        return s == null ? null : s.strip();
    }

    public record Action(
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = NO_CONTROL_CHARACTERS) String reason,
            @NotNull TimeEntryStatus expectedStatus) {

        public Action {
            reason = trim(reason);
        }
    }

    public record Correct(
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = NO_CONTROL_CHARACTERS) String reason,
            @NotNull TimeEntryStatus expectedStatus,
            @NotNull Instant expectedEffectiveTime,
            @NotNull @Pattern(regexp = LOCAL_DATE_TIME) String localTime) {

        public Correct {
            reason = trim(reason);
        }
    }

    public record Create(
            @NotNull Long employeeId,
            @NotNull TimeEntryType type,
            @NotNull LocalDate workDate,
            @NotNull @Pattern(regexp = LOCAL_DATE_TIME) String localTime,
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = NO_CONTROL_CHARACTERS) String reason) {

        public Create {
            reason = trim(reason);
        }
    }
}
