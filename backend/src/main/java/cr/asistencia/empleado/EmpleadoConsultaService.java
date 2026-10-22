package cr.asistencia.empleado;

import cr.asistencia.common.RecursoNoEncontradoException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class EmpleadoConsultaService {

    private final EmpleadoRepository empleadoRepository;

    public EmpleadoConsultaService(EmpleadoRepository empleadoRepository) {
        this.empleadoRepository = empleadoRepository;
    }

    public List<EmpleadoResumen> listar(Long empresaId) {
        return empleadoRepository.findAllByEmpresaIdOrderByNombre(empresaId).stream()
                .map(EmpleadoResumen::de)
                .toList();
    }

    // Another tenant's id is indistinguishable from a missing one: 404, never 403.
    public EmpleadoResumen obtener(Long empresaId, Long id) {
        return empleadoRepository.findByIdAndEmpresaId(id, empresaId)
                .map(EmpleadoResumen::de)
                .orElseThrow(RecursoNoEncontradoException::new);
    }
}
