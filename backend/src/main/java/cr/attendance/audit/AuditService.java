package cr.attendance.audit;

import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Own write transaction, whatever the caller is doing (a photo view happens after a read-only lookup).
     * details: ids and field names only, never a PIN, a name or a coordinate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long companyId, Long actorId, AuditAction action, AuditTargetType targetType,
            Long targetId, String details) {
        repository.save(new AuditLogEntry(companyId, actorId, action.name(), targetType.name(), targetId, details,
                Instant.now(clock)));
    }

    /**
     * Same transaction as the caller (MANDATORY: calling it without one is a programming error). Management
     * writers use it so the audit row and the change commit or roll back together, and so the insert never waits
     * on a row lock the caller itself holds. details: ids, enum values and field names only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(Long companyId, Long actorId, AuditAction action, AuditTargetType targetType,
            Long targetId, String details, String reasonCode, String reasonNote) {
        repository.save(new AuditLogEntry(companyId, actorId, action.name(), targetType.name(), targetId, details,
                reasonCode, reasonNote, Instant.now(clock)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(Long companyId, Long actorId, AuditAction action, AuditTargetType targetType,
            Long targetId, String details) {
        recordInTransaction(companyId, actorId, action, targetType, targetId, details, null, null);
    }
}
