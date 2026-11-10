package cr.attendance.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only. Plain ids, so this package depends on nothing but common. details: ids and field names only. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "details", length = 500)
    private String details;

    // Optional reason of an admin action (4b: PIN reset). Language-neutral code plus a short note without
    // digit runs. Never a PIN, a name or a code.
    @Column(name = "reason_code", length = 30)
    private String reasonCode;

    @Column(name = "reason_note", length = 200)
    private String reasonNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(Long companyId, Long actorId, String action, String targetType, Long targetId,
            String details, Instant createdAt) {
        this(companyId, actorId, action, targetType, targetId, details, null, null, createdAt);
    }

    public AuditLogEntry(Long companyId, Long actorId, String action, String targetType, Long targetId,
            String details, String reasonCode, String reasonNote, Instant createdAt) {
        this.reasonCode = reasonCode;
        this.reasonNote = reasonNote;
        this.companyId = companyId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getDetails() {
        return details;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonNote() {
        return reasonNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
