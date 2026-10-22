package cr.asistencia.marca;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface EvidenciaRepository extends Repository<Evidencia, Long> {

    Evidencia save(Evidencia evidencia);

    Optional<Evidencia> findByIdAndEmpresaId(Long id, Long empresaId);
}
