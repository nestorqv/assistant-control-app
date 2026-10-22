package cr.attendance.company;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// Tenant root: the only repository allowed to be unscoped. findByCode is used by login.
public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByCode(String code);
}
