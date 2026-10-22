package cr.attendance.timeentry;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface EvidenceRepository extends Repository<Evidence, Long> {

    Evidence save(Evidence evidence);

    Optional<Evidence> findByIdAndCompanyId(Long id, Long companyId);
}
