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
    INVALID_PIN;

    /** Message bundle key, e.g. error.INVALID_CREDENTIALS.title. */
    public String messageKey(String part) {
        return "error." + name() + "." + part;
    }
}
