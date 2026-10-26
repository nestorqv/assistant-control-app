package cr.attendance.timeentry;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TimeEntryRepository extends Repository<TimeEntry, Long> {

    TimeEntry save(TimeEntry timeEntry);

    Optional<TimeEntry> findByIdAndCompanyId(Long id, Long companyId);

    // Idempotency key: (company, client uuid).
    Optional<TimeEntry> findByCompanyIdAndClientUuid(Long companyId, UUID clientUuid);

    List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndWorkDateOrderByServerTime(Long companyId, Long employeeId,
            LocalDate workDate);

    // Open-shift window, newest first.
    List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndServerTimeAfterOrderByServerTimeDesc(Long companyId,
            Long employeeId, Instant after);
}
