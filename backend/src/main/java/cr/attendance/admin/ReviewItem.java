package cr.attendance.admin;

import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** One row of the review queue. No coordinates: the evidence viewer fetches the detail. */
public record ReviewItem(Employee employee, LocalDate workDate, boolean ownEntry, Entry entry,
        AdminEntrySummary pair) {

    public record Employee(Long id, String code, String name, String jobTitle) {
    }

    public record Entry(
            Long id,
            TimeEntryType type,
            Instant effectiveTime,
            Instant serverTime,
            TimeEntryStatus status,
            Set<ReviewReason> reviewReasons,
            TimeEntrySource source,
            Integer distanceMeters,
            Boolean insideGeofence,
            boolean hasPhoto,
            boolean hasGps,
            boolean adjusted,
            Instant deviceTime,
            Integer skewSeconds,
            boolean syncedLate,
            Integer gpsAccuracyMeters,
            Integer lateMinutes) {
    }

    public record Page(List<ReviewItem> items, int page, int size, long totalItems) {
    }

    /** pending counts every PENDING_REVIEW entry of the company; today is the company-local date. */
    public record Count(long pending, LocalDate today, String timeZone) {
    }
}
