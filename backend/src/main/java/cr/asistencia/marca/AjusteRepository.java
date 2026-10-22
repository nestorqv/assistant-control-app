package cr.asistencia.marca;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AjusteRepository extends Repository<Ajuste, Long> {

    Ajuste save(Ajuste ajuste);

    Optional<Ajuste> findByIdAndEmpresaId(Long id, Long empresaId);
}
