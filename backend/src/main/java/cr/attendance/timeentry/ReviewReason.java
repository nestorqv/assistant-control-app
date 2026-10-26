package cr.attendance.timeentry;

/** Why an entry needs an admin look. Order matters: it is the stored (sorted) order. */
public enum ReviewReason {
    NO_GPS, OUTSIDE_GEOFENCE, LOW_GPS_ACCURACY, SYNCED_LATE, DEVICE_CLOCK_AHEAD, SHIFT_TOO_LONG
}
