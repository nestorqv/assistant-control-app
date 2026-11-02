package cr.attendance.admin;

import cr.attendance.timeentry.TimeEntryType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Window an admin-typed time must fall in. Pure; the caller turns false into 400 INVALID_ENTRY_TIME. */
public final class CorrectionRules {

    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);
    static final Duration CLOCK_IN_EARLY_WINDOW = Duration.ofHours(12);

    private CorrectionRules() {
    }

    /**
     * @param pairEffective the effective time of the other entry of the work date, or null when there is none
     *                      (for a CLOCK_OUT it is the CLOCK_IN and must exist)
     * @param maxShift      the same maximum shift length as the PWA rules (app.time-entry.max-shift-duration)
     */
    public static boolean isValid(TimeEntryType type, LocalDate workDate, Instant candidate, Instant now,
            ZoneId zone, Instant pairEffective, Duration maxShift) {
        if (candidate.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return false;
        }
        if (type == TimeEntryType.CLOCK_IN) {
            // The window covers a shift that starts just before midnight of the work date.
            Instant earliest = workDate.atStartOfDay(zone).toInstant().minus(CLOCK_IN_EARLY_WINDOW);
            Instant latest = workDate.plusDays(1).atStartOfDay(zone).toInstant();
            if (candidate.isBefore(earliest) || candidate.isAfter(latest)) {
                return false;
            }
            return pairEffective == null || candidate.isBefore(pairEffective);
        }
        if (pairEffective == null || !candidate.isAfter(pairEffective)) {
            return false;
        }
        return !candidate.isAfter(pairEffective.plus(maxShift));
    }
}
