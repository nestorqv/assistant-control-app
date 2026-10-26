package cr.attendance.common;

import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 error bodies with an extra "code" (English, never translated). "title" and "detail" are
 * localized from the message bundle using the locale resolved from Accept-Language. Bodies never echo
 * submitted input.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    // Spring fills a null "instance" with the request path, which would echo the URL the client sent.
    // Constant on purpose: failure bodies must also stay identical to each other (no enumeration).
    private static final URI OPAQUE_INSTANCE = URI.create("urn:attendance:error");

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String RETRY_AFTER_BUSY = "5";

    private final MessageSource messages;

    public ApiExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalidCredentials() {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, null);
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    ResponseEntity<ProblemDetail> tooManyAttempts(TooManyAttemptsException e) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS,
                String.valueOf(e.getRetryAfter().toSeconds()));
    }

    @ExceptionHandler(InvalidPinException.class)
    ResponseEntity<ProblemDetail> invalidPin() {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PIN, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound() {
        return respond(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, null);
    }

    // Last resort: a fixed 500 body instead of the Boot error page (which echoes the request path).
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, null);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> apiException(ApiException e) {
        // 503 SERVER_BUSY: tell well-behaved clients (the PWA queue) when to retry.
        return respond(e.status(), e.code(), e.status() == HttpStatus.SERVICE_UNAVAILABLE ? RETRY_AFTER_BUSY : null);
    }

    // A row lock held too long (lock_timeout) is a busy server, not a bug: ask the client to retry.
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> lockTimeout(PessimisticLockingFailureException e) {
        log.warn("Lock wait gave up: {}", e.getClass().getSimpleName());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVER_BUSY, RETRY_AFTER_BUSY);
    }

    // Plain MultipartException (malformed body) is not handled by the base class. The size-limit subclass,
    // MaxUploadSizeExceededException, has its own more specific handler there and gives 413.
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> malformedMultipart() {
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, null);
    }

    /**
     * Every exception handled by the base class (validation, unreadable body, type mismatch, 405, 415,
     * missing parameter...) ends here. The base body is discarded because it can echo the submitted
     * value or an exception message; only the status and the headers (Allow, Accept) are kept.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(headers);
        merged.set(HttpHeaders.CONTENT_LANGUAGE, LocaleContextHolder.getLocale().getLanguage());
        return createResponseEntity(problem(statusCode, codeFor(statusCode)), merged, statusCode, request);
    }

    private ResponseEntity<ProblemDetail> respond(HttpStatus status, ErrorCode code, String retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_LANGUAGE, LocaleContextHolder.getLocale().getLanguage());
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
        }
        return builder.body(problem(status, code));
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    private ProblemDetail problem(HttpStatusCode status, ErrorCode code) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                messages.getMessage(code.messageKey("detail"), null, locale));
        problem.setTitle(messages.getMessage(code.messageKey("title"), null, locale));
        problem.setProperty("code", code.name());
        problem.setInstance(OPAQUE_INSTANCE);
        return problem;
    }
}
