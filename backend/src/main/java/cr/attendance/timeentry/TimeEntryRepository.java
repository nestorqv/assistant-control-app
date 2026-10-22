package cr.attendance.timeentry;

import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface TimeEntryRepository extends Repository<TimeEntry, Long> {

    TimeEntry save(TimeEntry timeEntry);

    Optional<TimeEntry> findByIdAndCompanyId(Long id, Long companyId);
}
