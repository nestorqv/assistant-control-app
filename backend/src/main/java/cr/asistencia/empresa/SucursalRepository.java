package cr.asistencia.empresa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface SucursalRepository extends Repository<Sucursal, Long> {

    Sucursal save(Sucursal sucursal);

    Optional<Sucursal> findByIdAndEmpresaId(Long id, Long empresaId);

    List<Sucursal> findAllByEmpresaIdOrderByNombre(Long empresaId);
}
