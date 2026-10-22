package cr.asistencia.marca;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface MarcaRepository extends Repository<Marca, Long> {

    Marca save(Marca marca);

    Optional<Marca> findByIdAndEmpresaId(Long id, Long empresaId);
}
