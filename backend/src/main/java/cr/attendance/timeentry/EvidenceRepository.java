package cr.attendance.timeentry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface EvidenceRepository extends Repository<Evidence, Long> {

    Evidence save(Evidence evidence);

    Optional<Evidence> findByIdAndCompanyId(Long id, Long companyId);

    Optional<Evidence> findByCompanyIdAndTimeEntryIdAndType(Long companyId, Long timeEntryId, EvidenceType type);

    // Callers pass a non-empty collection.
    List<Evidence> findAllByCompanyIdAndTimeEntryIdInAndType(Long companyId, Collection<Long> timeEntryIds,
            EvidenceType type);
}
