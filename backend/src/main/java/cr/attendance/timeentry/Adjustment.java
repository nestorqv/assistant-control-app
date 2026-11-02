package cr.attendance.timeentry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only (repository without update/delete, plus database triggers). One user action writes 1-3 rows. */
@Entity
@Table(name = "adjustment")
public class Adjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "time_entry_id", nullable = false)
    private Long timeEntryId;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private AdjustmentAction action;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    // Name of the changed field: status, effective_time, late_minutes or entry (ck_adjustment_field).
    @Column(name = "field", nullable = false, columnDefinition = "text")
    private String field;

    // Null when the adjustment creates an entry. Plain text, not a Jackson-bound JSON type.
    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", nullable = false, columnDefinition = "text")
    private String newValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Adjustment() {
    }

    public Adjustment(Long companyId, Long timeEntryId, Long adminId, AdjustmentAction action, String reason,
            String field, String oldValue, String newValue, Instant createdAt) {
        this.companyId = companyId;
        this.timeEntryId = timeEntryId;
        this.adminId = adminId;
        this.action = action;
        this.reason = reason;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getTimeEntryId() {
        return timeEntryId;
    }

    public Long getAdminId() {
        return adminId;
    }

    public AdjustmentAction getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public String getField() {
        return field;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
