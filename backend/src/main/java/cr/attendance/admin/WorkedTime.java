package cr.attendance.admin;

import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryStatus;
import java.time.Duration;

/** Worked minutes of one day, always from effective_time. Rejected entries never count. */
public final class WorkedTime {

    private WorkedTime() {
    }

    /** floor((out - in) / 1 min) when both exist and neither is REJECTED, otherwise null. */
    public static Long workedMinutes(TimeEntry in, TimeEntry out) {
        if (in == null || out == null || in.getStatus() == TimeEntryStatus.REJECTED
                || out.getStatus() == TimeEntryStatus.REJECTED) {
            return null;
        }
        return Math.max(0, Duration.between(in.getEffectiveTime(), out.getEffectiveTime()).toMinutes());
    }

    /** Payroll minutes: only when both entries are VALID. */
    public static long approvedMinutes(TimeEntry in, TimeEntry out) {
        Long worked = workedMinutes(in, out);
        boolean valid = worked != null && in.getStatus() == TimeEntryStatus.VALID
                && out.getStatus() == TimeEntryStatus.VALID;
        return valid ? worked : 0;
    }

    /** Minutes of a day where at least one entry still waits for review. */
    public static long pendingMinutes(TimeEntry in, TimeEntry out) {
        Long worked = workedMinutes(in, out);
        boolean pending = worked != null && (in.getStatus() == TimeEntryStatus.PENDING_REVIEW
                || out.getStatus() == TimeEntryStatus.PENDING_REVIEW);
        return pending ? worked : 0;
    }
}
