package cr.attendance.audit;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Append-only on purpose: save and scoped finds only. */
public interface AuditLogRepository extends Repository<AuditLogEntry, Long> {

    AuditLogEntry save(AuditLogEntry entry);

    List<AuditLogEntry> findAllByCompanyIdAndActionOrderByIdDesc(Long companyId, String action);
}
