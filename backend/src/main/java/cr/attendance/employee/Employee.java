package cr.attendance.employee;

import cr.attendance.common.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.OptimisticLock;

@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    // Null only for ADMIN (database CHECK).
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "job_title", length = 80)
    private String jobTitle;

    // Credential, lock and consent columns are excluded from optimistic locking: a PIN reset, an unlock, a login
    // or a consent never makes another admin's open profile form stale. "version" means profile version.
    @OptimisticLock(excluded = true)
    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OptimisticLock(excluded = true)
    @Column(name = "consent_at")
    private Instant consentAt;

    // Lock state changes through the atomic queries in EmployeeRepository, or through a mutator below while
    // the row is held FOR UPDATE by an admin writer (never both in one transaction).
    @OptimisticLock(excluded = true)
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @OptimisticLock(excluded = true)
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @OptimisticLock(excluded = true)
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected Employee() {
    }

    public Employee(Long companyId, Long branchId, String code, String name, String jobTitle,
            String pinHash, Role role) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.code = code;
        this.name = name;
        this.jobTitle = jobTitle;
        this.pinHash = pinHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public String getPinHash() {
        return pinHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getConsentAt() {
        return consentAt;
    }

    public Integer getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public Integer getVersion() {
        return version;
    }

    // Mutators for the admin writers. Each returns whether anything changed, so a no-op writes no audit row.
    // Values arrive already cleaned (NFC, stripped).

    public boolean updateProfile(String newName, String newJobTitle) {
        boolean changed = !Objects.equals(name, newName) || !Objects.equals(jobTitle, newJobTitle);
        name = newName;
        jobTitle = newJobTitle;
        return changed;
    }

    public boolean assignBranch(Long newBranchId) {
        boolean changed = !Objects.equals(branchId, newBranchId);
        branchId = newBranchId;
        return changed;
    }

    public boolean changeRole(Role newRole) {
        boolean changed = role != newRole;
        role = newRole;
        return changed;
    }

    public boolean activate() {
        boolean changed = !active;
        active = true;
        return changed;
    }

    public boolean deactivate() {
        boolean changed = active;
        active = false;
        return changed;
    }

    /** Invalidates every token issued so far (the "ver" claim no longer matches). */
    public void endSessions() {
        tokenVersion = tokenVersion + 1;
    }

    /** New PIN hash, lock cleared, all sessions ended. */
    public void resetPin(String newPinHash) {
        pinHash = newPinHash;
        failedAttempts = 0;
        lockedUntil = null;
        endSessions();
    }

    /** Clears the lock; false when there was nothing to clear. */
    public boolean unlock() {
        boolean changed = failedAttempts != 0 || lockedUntil != null;
        failedAttempts = 0;
        lockedUntil = null;
        return changed;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
