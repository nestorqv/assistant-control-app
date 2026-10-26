package cr.attendance.timeentry;

import cr.attendance.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Shift sequence rules over an employee's recent entries (any order). A shift is a CLOCK_IN with no
 * CLOCK_OUT on the same work_date. Pure functions: the caller passes the clock value and the local date.
 */
public final class ShiftResolver {

    private ShiftResolver() {
    }

    /**
     * violation is null when the entry may be recorded. shiftTooLong is true for a CLOCK_OUT that closes a
     * CLOCK_IN older than the maximum shift duration: it is accepted and sent to review, never rejected.
     */
    public record Decision(ErrorCode violation, LocalDate workDate, boolean shiftTooLong) {

        static Decision rejected(ErrorCode code) {
            return new Decision(code, null, false);
        }
    }

    public static Decision decide(TimeEntryType type, List<TimeEntry> recent, Instant now, Duration max,
            LocalDate localToday) {
        if (type == TimeEntryType.CLOCK_IN) {
            if (hasEntry(recent, TimeEntryType.CLOCK_IN, localToday)) {
                return Decision.rejected(hasEntry(recent, TimeEntryType.CLOCK_OUT, localToday)
                        ? ErrorCode.SHIFT_ALREADY_COMPLETED : ErrorCode.ALREADY_CLOCKED_IN);
            }
            if (openShift(recent, now, max, localToday).isPresent()) {
                return Decision.rejected(ErrorCode.ALREADY_CLOCKED_IN);
            }
            // A forgotten CLOCK_OUT of an earlier day does not block today.
            return new Decision(null, localToday, false);
        }
        // CLOCK_OUT closes the latest unclosed CLOCK_IN, even a forgotten one (flagged SHIFT_TOO_LONG).
        Optional<TimeEntry> candidate = unclosed(recent).findFirst();
        if (candidate.isEmpty()) {
            return Decision.rejected(ErrorCode.NOT_CLOCKED_IN);
        }
        TimeEntry clockIn = candidate.get();
        return new Decision(null, clockIn.getWorkDate(), !isFresh(clockIn, now, max));
    }

    /** Latest unclosed CLOCK_IN that is still inside the window, or that started on the current local day. */
    public static Optional<TimeEntry> openShift(List<TimeEntry> recent, Instant now, Duration max,
            LocalDate localToday) {
        return unclosed(recent).filter(e -> isOpen(e, now, max, localToday)).findFirst();
    }

    /** Latest unclosed CLOCK_IN of an earlier day that is past the window: a forgotten CLOCK_OUT. */
    public static Optional<TimeEntry> forgottenShift(List<TimeEntry> recent, Instant now, Duration max,
            LocalDate localToday) {
        return unclosed(recent).filter(e -> !isOpen(e, now, max, localToday)).findFirst();
    }

    public static NextAction nextAction(List<TimeEntry> recent, Instant now, Duration max, LocalDate localToday) {
        if (openShift(recent, now, max, localToday).isPresent()) {
            return NextAction.CLOCK_OUT;
        }
        return hasEntry(recent, TimeEntryType.CLOCK_IN, localToday) ? NextAction.NONE : NextAction.CLOCK_IN;
    }

    public static boolean isFresh(TimeEntry clockIn, Instant now, Duration max) {
        return clockIn.getServerTime().isAfter(now.minus(max));
    }

    private static boolean isOpen(TimeEntry clockIn, Instant now, Duration max, LocalDate localToday) {
        return isFresh(clockIn, now, max) || clockIn.getWorkDate().equals(localToday);
    }

    private static boolean hasEntry(List<TimeEntry> recent, TimeEntryType type, LocalDate workDate) {
        return recent.stream().anyMatch(e -> e.getType() == type && e.getWorkDate().equals(workDate));
    }

    // Newest first.
    private static Stream<TimeEntry> unclosed(List<TimeEntry> recent) {
        return recent.stream()
                .filter(e -> e.getType() == TimeEntryType.CLOCK_IN)
                .filter(in -> !hasEntry(recent, TimeEntryType.CLOCK_OUT, in.getWorkDate()))
                .sorted(Comparator.comparing(TimeEntry::getServerTime).reversed());
    }
}
