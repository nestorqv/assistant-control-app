package cr.attendance.admin;

import cr.attendance.audit.AuditService;
import cr.attendance.common.ApiException;
import cr.attendance.common.AuditAction;
import cr.attendance.common.AuditTargetType;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.employee.EmployeeRepository;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly schedule of one employee. The schedule has no version column: the PUT carries the week the editor
 * loaded ("expected"), compared under the employee row lock. Rows of kept days are updated in place, new days are
 * inserted and dropped days are removed with one bulk delete, so Hibernate's flush order (inserts, updates,
 * entity deletes last) can never break uq_work_schedule_employee_day. Stored late_minutes never change.
 */
@Service
public class AdminScheduleWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminScheduleWriter.class);

    /** A day in comparable form: times truncated to the minute (the column has seconds, the API does not). */
    private record Slot(int dayOfWeek, LocalTime start, LocalTime end, int grace) {

        static Slot of(ManagementRequests.ScheduleDay d) {
            return new Slot(d.dayOfWeek(), parse(d.startTime()), parse(d.endTime()), d.graceMinutes());
        }

        static Slot of(WorkSchedule w) {
            return new Slot(w.getDayOfWeek(), w.getStartTime().truncatedTo(ChronoUnit.MINUTES),
                    w.getEndTime().truncatedTo(ChronoUnit.MINUTES), w.getGraceMinutes());
        }

        ManagementRequests.ScheduleDay toDay() {
            return new ManagementRequests.ScheduleDay(dayOfWeek, start.toString(), end.toString(), grace);
        }

        private static LocalTime parse(String hhmm) {
            return LocalTime.parse(hhmm);
        }
    }

    private final WorkScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final AdminLocks locks;
    private final AuditService auditService;

    public AdminScheduleWriter(WorkScheduleRepository scheduleRepository, EmployeeRepository employeeRepository,
            AdminLocks locks, AuditService auditService) {
        this.scheduleRepository = scheduleRepository;
        this.employeeRepository = employeeRepository;
        this.locks = locks;
        this.auditService = auditService;
    }

    /** The week sorted by day, times as HH:mm. Another tenant's id is a 404. */
    @Transactional(readOnly = true)
    public List<ManagementRequests.ScheduleDay> get(Long companyId, Long employeeId) {
        employeeRepository.findByIdAndCompanyId(employeeId, companyId).orElseThrow(ResourceNotFoundException::new);
        return slots(scheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId)).stream()
                .map(Slot::toDay).toList();
    }

    @Transactional
    public List<ManagementRequests.ScheduleDay> replace(EmployeePrincipal principal, Long employeeId,
            ManagementRequests.ScheduleReplace request) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        locks.lockActorAndTarget(companyId, principal.employeeId(), employeeId);

        List<WorkSchedule> stored = scheduleRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId);
        List<Slot> current = slots(stored);
        Set<Slot> expected = request.expected().stream().map(Slot::of).collect(Collectors.toSet());
        if (!new HashSet<>(current).equals(expected)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.RECORD_CHANGED);
        }
        List<Slot> requested = request.days().stream().map(Slot::of)
                .sorted(Comparator.comparingInt(Slot::dayOfWeek)).toList();
        if (new HashSet<>(current).equals(new HashSet<>(requested))) {
            return current.stream().map(Slot::toDay).toList();
        }

        Map<Integer, WorkSchedule> byDay = new HashMap<>();
        for (WorkSchedule row : stored) {
            byDay.put(row.getDayOfWeek(), row);
        }
        for (Slot slot : requested) {
            WorkSchedule row = byDay.remove(slot.dayOfWeek());
            if (row != null) {
                row.change(slot.start(), slot.end(), slot.grace());
            } else {
                scheduleRepository.save(new WorkSchedule(companyId, employeeId, slot.dayOfWeek(), slot.start(),
                        slot.end(), slot.grace()));
            }
        }
        if (!byDay.isEmpty()) {
            // What is left in the map are the days that are no longer worked.
            scheduleRepository.deleteByCompanyIdAndEmployeeIdAndDayOfWeekIn(companyId, employeeId, byDay.keySet());
        }
        String days = requested.isEmpty() ? "none"
                : requested.stream().map(s -> String.valueOf(s.dayOfWeek())).collect(Collectors.joining(","));
        auditService.recordInTransaction(companyId, principal.employeeId(), AuditAction.SCHEDULE_UPDATE,
                AuditTargetType.EMPLOYEE, employeeId, "days=" + days);
        log.info("schedule_updated employee={} actor={} company={}", employeeId, principal.employeeId(), companyId);
        return requested.stream().map(Slot::toDay).toList();
    }

    private static List<Slot> slots(List<WorkSchedule> rows) {
        return rows.stream().map(Slot::of).sorted(Comparator.comparingInt(Slot::dayOfWeek)).toList();
    }
}
