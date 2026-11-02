package cr.attendance.timeentry;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TimeEntryRepository extends Repository<TimeEntry, Long> {

    TimeEntry save(TimeEntry timeEntry);

    Optional<TimeEntry> findByIdAndCompanyId(Long id, Long companyId);

    // Admin corrections: serializes two admins on one entry. Must be called inside a transaction, after the
    // employee row lock (always employee first, then entry).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TimeEntry> findWithLockByIdAndCompanyId(Long id, Long companyId);

    // Scalar on purpose: finding the owner must not load the entity before the lock is taken (stale state).
    @Query("select t.employeeId from TimeEntry t where t.id = :id and t.companyId = :companyId")
    Optional<Long> findEmployeeIdByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

    // Idempotency key: (company, client uuid).
    Optional<TimeEntry> findByCompanyIdAndClientUuid(Long companyId, UUID clientUuid);

    List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndWorkDateOrderByServerTime(Long companyId, Long employeeId,
            LocalDate workDate);

    Optional<TimeEntry> findByCompanyIdAndEmployeeIdAndWorkDateAndType(Long companyId, Long employeeId,
            LocalDate workDate, TimeEntryType type);

    // Open-shift window, newest first.
    List<TimeEntry> findAllByCompanyIdAndEmployeeIdAndServerTimeAfterOrderByServerTimeDesc(Long companyId,
            Long employeeId, Instant after);

    // Dashboard: one company, one day (ix_time_entry_company_work_date).
    List<TimeEntry> findAllByCompanyIdAndWorkDate(Long companyId, LocalDate workDate);

    long countByCompanyIdAndStatus(Long companyId, TimeEntryStatus status);

    // Review queue, oldest work date first. Two variants because a null employeeId cannot be typed safely.
    @Query("select t from TimeEntry t where t.companyId = :companyId and t.status = :status "
            + "and t.workDate >= :from and t.workDate <= :to "
            + "order by t.workDate asc, t.effectiveTime asc, t.id asc")
    Page<TimeEntry> findReviewPage(@Param("companyId") Long companyId, @Param("status") TimeEntryStatus status,
            @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    @Query("select t from TimeEntry t where t.companyId = :companyId and t.status = :status "
            + "and t.employeeId = :employeeId and t.workDate >= :from and t.workDate <= :to "
            + "order by t.workDate asc, t.effectiveTime asc, t.id asc")
    Page<TimeEntry> findReviewPageForEmployee(@Param("companyId") Long companyId,
            @Param("status") TimeEntryStatus status, @Param("employeeId") Long employeeId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    // Pairs for a page of review items (a superset: the caller matches employee and work date).
    List<TimeEntry> findAllByCompanyIdAndEmployeeIdInAndWorkDateIn(Long companyId, Collection<Long> employeeIds,
            Collection<LocalDate> workDates);
}
