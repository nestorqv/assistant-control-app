package cr.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.auth.InvalidPinException;
import cr.attendance.auth.TooManyAttemptsException;
import cr.attendance.support.TestMessages;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Every exception becomes a fixed ProblemDetail with an English code, localized title/detail and no
 * input echo, in both languages.
 */
class ApiExceptionHandlerTest {

    private static final String SECRET_VALUE = "secret-value-of-the-user";

    private final MessageSource messages = TestMessages.source();
    private final ApiExceptionHandler handler = new ApiExceptionHandler(messages);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private ProblemDetail check(ResponseEntity<?> r, HttpStatus expected, ErrorCode code, String lang) {
        assertThat(r.getStatusCode()).isEqualTo(expected);
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo(lang);
        assertThat(r.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail p = (ProblemDetail) r.getBody();
        assertThat(p.getProperties()).containsEntry("code", code.name());
        assertThat(p.getStatus()).isEqualTo(expected.value());
        Locale locale = Locale.of(lang);
        assertThat(p.getDetail()).isEqualTo(messages.getMessage(code.messageKey("detail"), null, locale));
        assertThat(p.getTitle()).isEqualTo(messages.getMessage(code.messageKey("title"), null, locale));
        assertThat(String.valueOf(p.getDetail()) + p.getTitle() + p.getProperties()).doesNotContain(SECRET_VALUE);
        return p;
    }

    private ProblemDetail handle(Exception ex, HttpStatus expected, ErrorCode code, String lang) throws Exception {
        LocaleContextHolder.setLocale(Locale.of(lang));
        ResponseEntity<Object> r = handler.handleException(ex, new ServletWebRequest(new MockHttpServletRequest()));
        return check(r, expected, code, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void unreadableBody(String lang) throws Exception {
        HttpInputMessage in = new MockHttpInputMessage(SECRET_VALUE.getBytes());
        handle(new HttpMessageNotReadableException("JSON parse error: " + SECRET_VALUE, in), HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void missingParameter(String lang) throws Exception {
        handle(new MissingServletRequestParameterException(SECRET_VALUE, "String"), HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_REQUEST, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void typeMismatch(String lang) throws Exception {
        handle(new MethodArgumentTypeMismatchException(SECRET_VALUE, Long.class, "id", null, new RuntimeException()),
                HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void methodNotSupported(String lang) throws Exception {
        handle(new HttpRequestMethodNotSupportedException("PUT", List.of("GET")), HttpStatus.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void mediaTypeNotSupported(String lang) throws Exception {
        handle(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void mediaTypeNotAcceptable(String lang) throws Exception {
        handle(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void invalidCredentials(String lang) {
        LocaleContextHolder.setLocale(Locale.of(lang));
        check(handler.invalidCredentials(), HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void tooManyAttemptsKeepsRetryAfter(String lang) {
        LocaleContextHolder.setLocale(Locale.of(lang));
        ResponseEntity<ProblemDetail> r = handler.tooManyAttempts(new TooManyAttemptsException(Duration.ofMinutes(5)));
        check(r, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS, lang);
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void invalidPin(String lang) {
        LocaleContextHolder.setLocale(Locale.of(lang));
        check(handler.invalidPin(), HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PIN, lang);
    }

    @ParameterizedTest
    @ValueSource(strings = {"es", "en"})
    void notFound(String lang) {
        LocaleContextHolder.setLocale(Locale.of(lang));
        check(handler.notFound(), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, lang);
    }
}
