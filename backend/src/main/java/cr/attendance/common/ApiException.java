package cr.attendance.common;

import org.springframework.http.HttpStatus;

/**
 * A business failure with a fixed status and a stable error code. The message is the code name only:
 * it never carries user data, so it is safe to log.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public ApiException(HttpStatus status, ErrorCode code) {
        super(code.name());
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
