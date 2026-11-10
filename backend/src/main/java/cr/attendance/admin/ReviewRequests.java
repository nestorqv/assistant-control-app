package cr.attendance.admin;

import cr.attendance.common.TextRules;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Request bodies of the review actions. Explicit records: nothing is bound to an entity and unknown properties
 * are ignored. The reason is NFC-normalized and stripped first (TextRules), then control, format (bidi,
 * zero-width), surrogate and line/paragraph separator characters are rejected (audit finding L-1).
 */
public final class ReviewRequests {

    static final String SAFE_TEXT = TextRules.SAFE_TEXT;
    static final String LOCAL_DATE_TIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$";

    private ReviewRequests() {
    }

    public record Action(
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = SAFE_TEXT) String reason,
            @NotNull TimeEntryStatus expectedStatus) {

        public Action {
            reason = TextRules.clean(reason);
        }
    }

    public record Correct(
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = SAFE_TEXT) String reason,
            @NotNull TimeEntryStatus expectedStatus,
            @NotNull Instant expectedEffectiveTime,
            @NotNull @Pattern(regexp = LOCAL_DATE_TIME) String localTime) {

        public Correct {
            reason = TextRules.clean(reason);
        }
    }

    public record Create(
            @NotNull Long employeeId,
            @NotNull TimeEntryType type,
            @NotNull LocalDate workDate,
            @NotNull @Pattern(regexp = LOCAL_DATE_TIME) String localTime,
            @NotNull @Size(min = 5, max = 500) @Pattern(regexp = SAFE_TEXT) String reason) {

        public Create {
            reason = TextRules.clean(reason);
        }
    }
}
