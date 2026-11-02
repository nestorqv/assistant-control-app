package cr.attendance.admin;

import cr.attendance.timeentry.AdjustmentAction;
import cr.attendance.timeentry.ReviewReason;
import cr.attendance.timeentry.TimeEntrySource;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Everything the evidence viewer shows about one entry. This is the only admin response that carries the
 * exact coordinates. It never carries the client uuid or a storage key.
 */
public record AdminEntryDetail(
        Long id,
        TimeEntryType type,
        LocalDate workDate,
        Employee employee,
        boolean ownEntry,
        TimeEntrySource source,
        Instant serverTime,
        Instant effectiveTime,
        Instant deviceTime,
        Integer skewSeconds,
        boolean syncedLate,
        TimeEntryStatus status,
        Set<ReviewReason> reviewReasons,
        Integer distanceMeters,
        Integer gpsAccuracyMeters,
        Boolean insideGeofence,
        Integer lateMinutes,
        boolean hasPhoto,
        boolean hasGps,
        boolean adjusted,
        Location location,
        Branch branch,
        AdminEntrySummary pair,
        List<AdjustmentView> adjustments) {

    public record Employee(Long id, String code, String name, String jobTitle) {
    }

    public record Location(double latitude, double longitude) {
    }

    /** The current branch values ("current radius"), not the ones at the time of the entry. */
    public record Branch(Long id, String name, double latitude, double longitude, int radiusMeters) {
    }

    /** selfApproved: the admin who acted is the employee of the entry. */
    public record AdjustmentView(Long id, AdjustmentAction action, String field, String oldValue, String newValue,
            String reason, String adminName, boolean selfApproved, Instant createdAt) {
    }
}
