package cr.attendance.common;

/** Machine-readable error codes. They never change with the language; titles and details do. */
public enum ErrorCode {
    INVALID_CREDENTIALS,
    TOO_MANY_ATTEMPTS,
    INVALID_REQUEST,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    UNSUPPORTED_MEDIA_TYPE,
    NOT_FOUND,
    INTERNAL_ERROR,
    INVALID_PIN,
    CONSENT_REQUIRED,
    NO_BRANCH_ASSIGNED,
    ALREADY_CLOCKED_IN,
    NOT_CLOCKED_IN,
    SHIFT_ALREADY_COMPLETED,
    CLIENT_UUID_CONFLICT,
    PHOTO_REQUIRED,
    INVALID_PHOTO,
    PAYLOAD_TOO_LARGE,
    SERVER_BUSY;

    /** Message bundle key, e.g. error.INVALID_CREDENTIALS.title. */
    public String messageKey(String part) {
        return "error." + name() + "." + part;
    }
}
