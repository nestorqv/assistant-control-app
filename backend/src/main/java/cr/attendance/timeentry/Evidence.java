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

@Entity
@Table(name = "evidence")
public class Evidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "time_entry_id", nullable = false)
    private Long timeEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private EvidenceType type;

    // Storage key, not a public URL. Null for GPS evidence.
    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Evidence() {
    }

    public Evidence(Long companyId, Long timeEntryId, EvidenceType type, String storageKey, Instant createdAt) {
        this.companyId = companyId;
        this.timeEntryId = timeEntryId;
        this.type = type;
        this.storageKey = storageKey;
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

    public EvidenceType getType() {
        return type;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
