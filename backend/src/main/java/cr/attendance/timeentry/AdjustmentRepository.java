package cr.attendance.timeentry;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AdjustmentRepository extends Repository<Adjustment, Long> {

    Adjustment save(Adjustment adjustment);

    Optional<Adjustment> findByIdAndCompanyId(Long id, Long companyId);
}
