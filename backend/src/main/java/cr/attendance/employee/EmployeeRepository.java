package cr.attendance.employee;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmployeeRepository extends Repository<Employee, Long> {

    Employee save(Employee employee);

    Optional<Employee> findByIdAndCompanyId(Long id, Long companyId);

    // Serializes every time-entry POST of one employee. Must be called inside a transaction.
    // No lock.timeout hint: Hibernate's PostgreSQL dialect ignores it. TimeEntryWriter bounds the wait
    // with SET LOCAL lock_timeout in the same transaction instead.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findWithLockByIdAndCompanyId(Long id, Long companyId);

    List<Employee> findAllByCompanyIdOrderByName(Long companyId);

    // Login lookup: companyId comes from the already-resolved company, not from the request.
    Optional<Employee> findByCompanyIdAndCode(Long companyId, String code);

    // One atomic UPDATE, in its own transaction so it commits even though the request ends in 401.
    // An already-locked account is left untouched (no increment, no extension).
    @Transactional
    @Modifying
    @Query("""
            update Employee e
               set e.failedAttempts = case when e.failedAttempts + 1 >= :maxAttempts
                                             then 0 else e.failedAttempts + 1 end,
                   e.lockedUntil = case when e.failedAttempts + 1 >= :maxAttempts
                                           then :lockUntil else e.lockedUntil end
             where e.id = :id and e.companyId = :companyId
               and (e.lockedUntil is null or e.lockedUntil <= :now)
            """)
    int recordFailedLogin(@Param("id") Long id, @Param("companyId") Long companyId,
            @Param("maxAttempts") int maxAttempts, @Param("lockUntil") Instant lockUntil,
            @Param("now") Instant now);

    // Never clears an active lock: if the account got locked while this attempt was comparing the PIN,
    // 0 rows are updated and the caller must reject the login. An expired lock is cleared.
    @Transactional
    @Modifying
    @Query("""
            update Employee e set e.failedAttempts = 0, e.lockedUntil = null
             where e.id = :id and e.companyId = :companyId
               and (e.lockedUntil is null or e.lockedUntil <= :now)
            """)
    int recordSuccessfulLogin(@Param("id") Long id, @Param("companyId") Long companyId,
            @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            update Employee e set e.consentAt = :consentAt
             where e.id = :id and e.companyId = :companyId and e.consentAt is null
            """)
    int recordConsent(@Param("id") Long id, @Param("companyId") Long companyId,
            @Param("consentAt") Instant consentAt);

    // Logout: invalidates every token issued so far for this employee (the "ver" claim no longer matches).
    @Transactional
    @Modifying
    @Query("""
            update Employee e set e.tokenVersion = e.tokenVersion + 1
             where e.id = :id and e.companyId = :companyId
            """)
    int incrementTokenVersion(@Param("id") Long id, @Param("companyId") Long companyId);
}
