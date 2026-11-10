package cr.attendance.admin;

/** Optional preset reason of a PIN reset. The audit_log.reason_code column only checks the format (allow-list here). */
public enum PinResetReason {
    FORGOT_PIN,
    ACCOUNT_LOCKED,
    POSSIBLE_MISUSE,
    OTHER
}
