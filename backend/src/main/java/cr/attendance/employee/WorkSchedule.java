package cr.attendance.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

@Entity
@Table(name = "work_schedule")
public class WorkSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    // ISO: 1 = Monday.
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    // LocalTimeJdbcType binds the LocalTime as-is (setObject). The default TIME binding goes through
    // java.sql.Time and shifts the value by the JVM zone because hibernate.jdbc.time_zone is UTC.
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "grace_minutes", nullable = false)
    private Integer graceMinutes = 0;

    protected WorkSchedule() {
    }

    public WorkSchedule(Long companyId, Long employeeId, Integer dayOfWeek, LocalTime startTime,
            LocalTime endTime, Integer graceMinutes) {
        this.companyId = companyId;
        this.employeeId = employeeId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.graceMinutes = graceMinutes;
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

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Integer getGraceMinutes() {
        return graceMinutes;
    }
}
