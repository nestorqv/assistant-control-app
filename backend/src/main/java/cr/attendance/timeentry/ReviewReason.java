package cr.attendance.timeentry;

/** Why an entry needs an admin look. Order matters: it is the stored (sorted) order. */
public enum ReviewReason {
    NO_GPS, OUTSIDE_GEOFENCE, LOW_GPS_ACCURACY, SYNCED_LATE, DEVICE_CLOCK_AHEAD, SHIFT_TOO_LONG,
    // Coordinates sent without a usable accuracy (missing or under 1 m): a scripted-client signal.
    NO_GPS_ACCURACY,
    // Clock-out long after the scheduled end (tolerance: app.time-entry.after-schedule-tolerance).
    CLOCK_OUT_AFTER_SCHEDULE
}
