package cr.asistencia.common;

import cr.asistencia.auth.CredencialesInvalidasException;
import cr.asistencia.auth.DemasiadosIntentosException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 error bodies with an extra "codigo". Bodies never echo submitted input. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String MENSAJE_CREDENCIALES =
            "Empresa, código o PIN incorrectos. Si falló varias veces, espere 15 minutos o consulte a su administrador.";
    static final String MENSAJE_DEMASIADOS =
            "Demasiados intentos. Intente de nuevo en unos minutos.";

    @ExceptionHandler(CredencialesInvalidasException.class)
    ProblemDetail credencialesInvalidas() {
        return problema(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", MENSAJE_CREDENCIALES);
    }

    @ExceptionHandler(DemasiadosIntentosException.class)
    ResponseEntity<ProblemDetail> demasiadosIntentos(DemasiadosIntentosException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getReintentarEn().toSeconds()))
                .body(problema(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS", MENSAJE_DEMASIADOS));
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    ProblemDetail noEncontrado() {
        return problema(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", "Recurso no encontrado.");
    }

    /**
     * Every exception handled by the base class (validation, unreadable body, type mismatch, 405, 415,
     * missing parameter...) ends here. The base body is discarded because it can echo the submitted
     * value or an exception message; only the status and the headers (Allow, Accept) are kept.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(statusCode, detalle(statusCode));
        problem.setProperty("codigo", codigo(statusCode));
        problem.setInstance(instanciaOpaca());
        return createResponseEntity(problem, headers, statusCode, request);
    }

    private static String codigo(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "NO_ENCONTRADO";
            case 405 -> "METODO_NO_PERMITIDO";
            case 406 -> "FORMATO_NO_ACEPTABLE";
            case 415 -> "TIPO_CONTENIDO_NO_SOPORTADO";
            default -> status.is5xxServerError() ? "ERROR_INTERNO" : "SOLICITUD_INVALIDA";
        };
    }

    private static String detalle(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "Recurso no encontrado.";
            case 405 -> "Método no permitido.";
            case 406 -> "Formato de respuesta no disponible.";
            case 415 -> "Tipo de contenido no soportado.";
            default -> status.is5xxServerError() ? "Error interno." : "La solicitud no es válida.";
        };
    }

    // Spring fills a null "instance" with the request path, which would echo the URL the client sent.
    // Constant on purpose: failure bodies must also stay identical to each other (no enumeration).
    private static final URI INSTANCIA = URI.create("urn:asistencia:error");

    private static URI instanciaOpaca() {
        return INSTANCIA;
    }

    private static ProblemDetail problema(HttpStatus status, String codigo, String detalle) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detalle);
        problem.setProperty("codigo", codigo);
        problem.setInstance(instanciaOpaca());
        return problem;
    }
}
