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
}
