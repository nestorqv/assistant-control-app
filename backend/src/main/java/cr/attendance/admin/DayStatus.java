package cr.attendance.admin;

/** Declared in priority order: the first matching rule wins (see DayStatusResolver). */
public enum DayStatus {
    REJECTED, OUTSIDE_AREA, NEEDS_REVIEW, MISSING_CLOCK_OUT, COMPLETE, LATE, ON_TIME, UNSCHEDULED,
    NOT_YET_DUE, NOT_CLOCKED, DAY_OFF
}
