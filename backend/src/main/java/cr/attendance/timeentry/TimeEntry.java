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
import java.util.UUID;

@Entity
@Table(name = "time_entry")
public class TimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TimeEntryType type;

    @Column(name = "server_time", nullable = false)
    private Instant serverTime;

    @Column(name = "device_time")
    private Instant deviceTime;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "distance_meters")
    private Integer distanceMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private TimeEntrySource source;

    @Column(name = "synced_late", nullable = false)
    private boolean syncedLate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TimeEntryStatus status = TimeEntryStatus.VALID;

    @Column(name = "client_uuid", nullable = false)
    private UUID clientUuid;

    protected TimeEntry() {
    }

    public TimeEntry(Long companyId, Long employeeId, Long branchId, TimeEntryType type, Instant serverTime,
            TimeEntrySource source, UUID clientUuid) {
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.branchId = branchId;
        this.type = type;
        this.serverTime = serverTime;
        this.source = source;
        this.clientUuid = clientUuid;
    }

    public Long getId() {
        return id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Long getBranchId() {
        return branchId;
    }

    public TimeEntryType getType() {
        return type;
    }

    public Instant getServerTime() {
        return serverTime;
    }

    public Instant getDeviceTime() {
        return deviceTime;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public Integer getDistanceMeters() {
        return distanceMeters;
    }

    public TimeEntrySource getSource() {
        return source;
    }

    public boolean isSyncedLate() {
        return syncedLate;
    }

    public TimeEntryStatus getStatus() {
        return status;
    }

    public UUID getClientUuid() {
        return clientUuid;
    }
}
