package cr.asistencia.common;

public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException() {
        super("Recurso no encontrado");
    }
}
