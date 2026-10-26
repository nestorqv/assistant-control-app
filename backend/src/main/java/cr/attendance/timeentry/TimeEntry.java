package cr.attendance.timeentry;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalDateJdbcType;

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

    // Company-local date the shift belongs to. LocalDateJdbcType binds the LocalDate as-is: the default DATE
    // binding goes through java.sql.Date and can shift a day when hibernate.jdbc.time_zone=UTC and the JVM
    // zone is east of UTC (same bug class as WorkSchedule.startTime).
    @JdbcType(LocalDateJdbcType.class)
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    // Signed: server_time minus deviceSentAt (or deviceTime when there is no usable deviceSentAt).
    @Column(name = "skew_seconds")
    private Integer skewSeconds;

    // Computed once at CLOCK_IN; null for CLOCK_OUT or when there is no schedule that day.
    @Column(name = "late_minutes")
    private Integer lateMinutes;

    @Column(name = "gps_accuracy_meters")
    private Integer gpsAccuracyMeters;

    @Convert(converter = ReviewReasonsConverter.class)
    @Column(name = "review_reasons", length = 200)
    private Set<ReviewReason> reviewReasons = EnumSet.noneOf(ReviewReason.class);

    protected TimeEntry() {
    }

    public TimeEntry(Long companyId, Long employeeId, Long branchId, TimeEntryType type, Instant serverTime,
            TimeEntrySource source, UUID clientUuid, LocalDate workDate) {
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.branchId = branchId;
        this.type = type;
        this.serverTime = serverTime;
        this.source = source;
        this.clientUuid = clientUuid;
        this.workDate = workDate;
    }

    /** Device and review data, set once when the entry is created. */
    public TimeEntry withEvidence(Instant deviceTime, Double latitude, Double longitude, Integer distanceMeters,
            Integer gpsAccuracyMeters, Integer skewSeconds, Integer lateMinutes, boolean syncedLate,
            TimeEntryStatus status, Set<ReviewReason> reviewReasons) {
        this.deviceTime = deviceTime;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceMeters = distanceMeters;
        this.gpsAccuracyMeters = gpsAccuracyMeters;
        this.skewSeconds = skewSeconds;
        this.lateMinutes = lateMinutes;
        this.syncedLate = syncedLate;
        this.status = status;
        this.reviewReasons = reviewReasons.isEmpty() ? EnumSet.noneOf(ReviewReason.class)
                : EnumSet.copyOf(reviewReasons);
        return this;
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

    public LocalDate getWorkDate() {
        return workDate;
    }

    public Integer getSkewSeconds() {
        return skewSeconds;
    }

    public Integer getLateMinutes() {
        return lateMinutes;
    }

    public Integer getGpsAccuracyMeters() {
        return gpsAccuracyMeters;
    }

    public Set<ReviewReason> getReviewReasons() {
        return reviewReasons;
    }

    @Override
    public String toString() {
        // No coordinates in logs (Ley 8968).
        return "TimeEntry[id=" + id + ", type=" + type + ", status=" + status + "]";
    }
}
