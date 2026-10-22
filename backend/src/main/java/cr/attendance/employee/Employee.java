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
import java.time.Instant;

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

    @Column(name = "pin_hash", nullable = false, length = 100)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "consent_at")
    private Instant consentAt;

    // Lock state is changed only by the atomic queries in EmployeeRepository.
    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 0;

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
}
