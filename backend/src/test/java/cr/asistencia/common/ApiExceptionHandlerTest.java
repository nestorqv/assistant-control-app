package cr.asistencia.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
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

/** Every exception of the base handler becomes a fixed ProblemDetail with a codigo and no input echo. */
class ApiExceptionHandlerTest {

    private static final String SECRETO = "valor-secreto-del-usuario";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private ProblemDetail manejar(Exception ex, HttpStatus esperado, String codigo) throws Exception {
        ResponseEntity<Object> r = handler.handleException(ex, new ServletWebRequest(new MockHttpServletRequest()));
        assertThat(r.getStatusCode()).isEqualTo(esperado);
        assertThat(r.getBody()).isInstanceOf(ProblemDetail.class);
        ProblemDetail p = (ProblemDetail) r.getBody();
        assertThat(p.getProperties()).containsEntry("codigo", codigo);
        assertThat(p.getStatus()).isEqualTo(esperado.value());
        assertThat(String.valueOf(p.getDetail()) + p.getTitle() + p.getProperties()).doesNotContain(SECRETO);
        return p;
    }

    @Test
    void unreadableBody() throws Exception {
        HttpInputMessage in = new MockHttpInputMessage(SECRETO.getBytes());
        manejar(new HttpMessageNotReadableException("JSON parse error: " + SECRETO, in), HttpStatus.BAD_REQUEST,
                "SOLICITUD_INVALIDA");
    }

    @Test
    void missingParameter() throws Exception {
        manejar(new MissingServletRequestParameterException(SECRETO, "String"), HttpStatus.BAD_REQUEST,
                "SOLICITUD_INVALIDA");
    }

    @Test
    void typeMismatch() throws Exception {
        manejar(new MethodArgumentTypeMismatchException(SECRETO, Long.class, "id", null, new RuntimeException()),
                HttpStatus.BAD_REQUEST, "SOLICITUD_INVALIDA");
    }

    @Test
    void methodNotSupported() throws Exception {
        manejar(new HttpRequestMethodNotSupportedException("PUT", List.of("GET")), HttpStatus.METHOD_NOT_ALLOWED,
                "METODO_NO_PERMITIDO");
    }

    @Test
    void mediaTypeNotSupported() throws Exception {
        manejar(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "TIPO_CONTENIDO_NO_SOPORTADO");
    }

    @Test
    void mediaTypeNotAcceptable() throws Exception {
        manejar(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                HttpStatus.NOT_ACCEPTABLE, "FORMATO_NO_ACEPTABLE");
    }
}
