package cr.attendance.timeentry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Append-only on purpose: no update and no delete methods. */
public interface AdjustmentRepository extends Repository<Adjustment, Long> {

    Adjustment save(Adjustment adjustment);

    Optional<Adjustment> findByIdAndCompanyId(Long id, Long companyId);

    List<Adjustment> findAllByCompanyIdAndTimeEntryIdOrderByCreatedAtAscIdAsc(Long companyId, Long timeEntryId);

    // Callers pass a non-empty collection.
    @Query("select distinct a.timeEntryId from Adjustment a where a.companyId = :companyId "
            + "and a.timeEntryId in :timeEntryIds")
    List<Long> findTimeEntryIdsWithAdjustments(@Param("companyId") Long companyId,
            @Param("timeEntryIds") Collection<Long> timeEntryIds);
}
