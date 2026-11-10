package cr.attendance.audit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Append-only on purpose: save and scoped finds only. */
public interface AuditLogRepository extends Repository<AuditLogEntry, Long> {

    AuditLogEntry save(AuditLogEntry entry);

    List<AuditLogEntry> findAllByCompanyIdAndActionOrderByIdDesc(Long companyId, String action);

    // Latest row of one action on one target (the "last PIN reset" line).
    Optional<AuditLogEntry> findFirstByCompanyIdAndActionAndTargetTypeAndTargetIdOrderByCreatedAtDescIdDesc(
            Long companyId, String action, String targetType, Long targetId);
}
