package cr.attendance.employee;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
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

    // Codes are never reused, not even by a deactivated employee.
    boolean existsByCompanyIdAndCode(Long companyId, String code);

    // Only active employees block deactivating a branch.
    long countByCompanyIdAndBranchIdAndActiveTrue(Long companyId, Long branchId);

    /** The calling admin as read under FOR KEY SHARE (a projection: the entity is not loaded). */
    interface ActorRow {
        String getRole();

        boolean getActive();
    }

    // Lock order step 1 (employee PUT / activate / deactivate only): every active admin of the company, by id,
    // FOR NO KEY UPDATE. That mode does not conflict with the FOR KEY SHARE that audit, adjustment and
    // time_entry inserts take on the rows they reference, so concurrent reviews and clock-ins do not stall.
    @Query(value = "select id from employee where company_id = :companyId and role = 'ADMIN' and active = true "
            + "order by id for no key update", nativeQuery = true)
    List<Long> lockActiveAdminIds(@Param("companyId") Long companyId);

    // Lock order step 2: the acting admin FOR KEY SHARE. Conflicts only with FOR UPDATE, so it queues behind a
    // writer that is changing this very row and makes the later audit FK check non-blocking.
    @Query(value = "select role as role, active as active from employee where id = :id and company_id = :companyId "
            + "for key share", nativeQuery = true)
    Optional<ActorRow> lockActorForKeyShare(@Param("id") Long id, @Param("companyId") Long companyId);

    // Dashboard rows: everyone who can clock in (active, with a branch). Admins without a branch have no entries.
    List<Employee> findAllByCompanyIdAndActiveTrueAndBranchIdIsNotNull(Long companyId);

    // Callers pass a non-empty collection.
    List<Employee> findAllByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

    @Query("select e.branchId, count(e) from Employee e where e.companyId = :companyId and e.active = true "
            + "and e.branchId is not null group by e.branchId")
    List<Object[]> countActiveByBranch(@Param("companyId") Long companyId);

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
