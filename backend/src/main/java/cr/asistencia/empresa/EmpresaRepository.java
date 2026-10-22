package cr.asistencia.empresa;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Tenant root: the only repository allowed to be unscoped. findByCodigo is used by login.
public interface EmpresaRepository extends JpaRepository<Empresa, Long> {

    Optional<Empresa> findByCodigo(String codigo);
}
