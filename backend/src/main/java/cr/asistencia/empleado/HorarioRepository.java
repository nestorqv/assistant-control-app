package cr.asistencia.empleado;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface HorarioRepository extends Repository<Horario, Long> {

    Horario save(Horario horario);

    List<Horario> findAllByEmpresaIdAndEmpleadoId(Long empresaId, Long empleadoId);
}
