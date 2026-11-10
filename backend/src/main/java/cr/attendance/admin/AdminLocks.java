package cr.attendance.admin;

import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.common.Role;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.timeentry.TimeEntryProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The shared lock protocol of every admin writer (and of the 4a ReviewWriter). Always this order, never the
 * reverse:
 * <ol>
 *   <li>company admin set FOR NO KEY UPDATE (employee PUT / activate / deactivate only);</li>
 *   <li>the acting admin FOR KEY SHARE together with the target employee FOR UPDATE, in ascending id order;</li>
 *   <li>the branch (FOR SHARE to assign, FOR UPDATE to edit or deactivate);</li>
 *   <li>the time entry (ReviewWriter only).</li>
 * </ol>
 * Why the actor goes first: every audit_log, adjustment and time_entry insert takes FOR KEY SHARE on the employee
 * row it references, which conflicts with FOR UPDATE. Holding the actor's KEY SHARE before any wait removes the
 * cycles between two admins acting on each other. Must be called inside a transaction.
 */
@Component
public class AdminLocks {

    private static final HibernateJpaDialect DIALECT = new HibernateJpaDialect();

    private final EmployeeRepository employeeRepository;
    private final TimeEntryProperties props;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminLocks(EmployeeRepository employeeRepository, TimeEntryProperties props) {
        this.employeeRepository = employeeRepository;
        this.props = props;
    }

    /** Bounds the row-lock wait in SQL. A timeout becomes 503 SERVER_BUSY. First statement of every writer. */
    public void applyLockTimeout() {
        entityManager.createNativeQuery("select set_config('lock_timeout', ?1, true)")
                .setParameter(1, props.lockTimeout().toMillis() + "ms").getSingleResult();
    }

    /**
     * Flushes pending changes now, so a constraint violation surfaces inside the writer as a
     * DataIntegrityViolationException (the repositories translate their own calls; a direct flush is not
     * translated) and the entity versions are final when the response is built.
     */
    public void flush() {
        try {
            entityManager.flush();
        } catch (RuntimeException e) {
            DataAccessException translated = DIALECT.translateExceptionIfPossible(e);
            throw translated != null ? translated : e;
        }
    }

    /** Step 1. Returns the ids of the company's active admins, now locked. */
    public List<Long> lockAdminSet(Long companyId) {
        return employeeRepository.lockActiveAdminIds(companyId);
    }

    /** Step 2 without a target employee (branch writers, missing entry): the actor FOR KEY SHARE only. */
    public void lockActor(Long companyId, Long actorId) {
        lockActorAndTarget(companyId, actorId, null);
    }

    /**
     * Step 2. Locks the actor (FOR KEY SHARE) and the target (FOR UPDATE) in ascending id order, then checks the
     * actor: it must still be an active ADMIN, else an empty 403 and nothing is written. The actor check comes
     * before the target 404, so a demoted admin learns nothing about other rows. When actor and target are the
     * same row only the FOR UPDATE is taken and the role is read from the loaded entity.
     *
     * @return the locked target, or null when targetId is null
     */
    public Employee lockActorAndTarget(Long companyId, Long actorId, Long targetId) {
        boolean sameRow = targetId != null && targetId.equals(actorId);
        boolean actorIsAdmin;
        Employee target = null;
        boolean targetMissing = false;
        if (sameRow) {
            Optional<Employee> self = employeeRepository.findWithLockByIdAndCompanyId(actorId, companyId);
            actorIsAdmin = self.map(e -> e.isActive() && e.getRole() == Role.ADMIN).orElse(false);
            target = self.orElse(null);
            targetMissing = self.isEmpty();
        } else if (targetId != null && targetId < actorId) {
            Optional<Employee> found = employeeRepository.findWithLockByIdAndCompanyId(targetId, companyId);
            target = found.orElse(null);
            targetMissing = found.isEmpty();
            actorIsAdmin = actorIsActiveAdmin(companyId, actorId);
        } else {
            actorIsAdmin = actorIsActiveAdmin(companyId, actorId);
            if (targetId != null) {
                Optional<Employee> found = employeeRepository.findWithLockByIdAndCompanyId(targetId, companyId);
                target = found.orElse(null);
                targetMissing = found.isEmpty();
            }
        }
        if (!actorIsAdmin) {
            throw new AccessDeniedException("Actor is not an active administrator");
        }
        if (targetMissing) {
            throw new ResourceNotFoundException();
        }
        return target;
    }

    private boolean actorIsActiveAdmin(Long companyId, Long actorId) {
        return employeeRepository.lockActorForKeyShare(actorId, companyId)
                .map(a -> a.getActive() && Role.ADMIN.name().equals(a.getRole()))
                .orElse(false);
    }
}
