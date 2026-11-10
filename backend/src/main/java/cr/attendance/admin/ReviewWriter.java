package cr.attendance.admin;

import cr.attendance.common.ApiException;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.employee.WorkScheduleRepository;
import cr.attendance.timeentry.Adjustment;
import cr.attendance.timeentry.AdjustmentAction;
import cr.attendance.timeentry.AdjustmentRepository;
import cr.attendance.timeentry.LatenessCalculator;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryProperties;
import cr.attendance.timeentry.TimeEntryRepository;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional part of the review actions. Lock order is the shared AdminLocks protocol: the acting admin
 * (FOR KEY SHARE) and the entry's employee (FOR UPDATE, the row the PWA POST also locks) in id order, then the
 * time entry. The actor is re-checked under the lock (an admin demoted a moment ago gets an empty 403).
 * Adjustment rows are written in the same transaction as the change, so either both exist or neither does.
 * server_time and work_date are never touched.
 */
@Service
public class ReviewWriter {

    private static final Logger log = LoggerFactory.getLogger(ReviewWriter.class);
    private static final int MAX_YEARS_FROM_TODAY = 5;

    private final TimeEntryRepository timeEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final CompanyRepository companyRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final TimeEntryProperties props;
    private final AdminLocks locks;
    private final Clock clock;

    public ReviewWriter(TimeEntryRepository timeEntryRepository, EmployeeRepository employeeRepository,
            BranchRepository branchRepository, CompanyRepository companyRepository,
            WorkScheduleRepository workScheduleRepository, AdjustmentRepository adjustmentRepository,
            TimeEntryProperties props, AdminLocks locks, Clock clock) {
        this.timeEntryRepository = timeEntryRepository;
        this.employeeRepository = employeeRepository;
        this.branchRepository = branchRepository;
        this.companyRepository = companyRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.props = props;
        this.locks = locks;
        this.clock = clock;
    }

    @Transactional
    public Long approve(EmployeePrincipal principal, Long id, ReviewRequests.Action request) {
        TimeEntry entry = lockEntry(principal, id, request.expectedStatus());
        if (entry.getStatus() != TimeEntryStatus.PENDING_REVIEW && entry.getStatus() != TimeEntryStatus.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_CHANGE);
        }
        Instant now = Instant.now(clock);
        TimeEntryStatus old = entry.getStatus();
        entry.changeStatus(TimeEntryStatus.VALID);
        record(principal, entry, AdjustmentAction.APPROVE, request.reason(), "status", old.name(), "VALID", now);
        log.info("Time entry {} approved by admin {}", id, principal.employeeId());
        return id;
    }

    @Transactional
    public Long reject(EmployeePrincipal principal, Long id, ReviewRequests.Action request) {
        TimeEntry entry = lockEntry(principal, id, request.expectedStatus());
        if (entry.getStatus() != TimeEntryStatus.PENDING_REVIEW && entry.getStatus() != TimeEntryStatus.VALID) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_CHANGE);
        }
        Instant now = Instant.now(clock);
        TimeEntryStatus old = entry.getStatus();
        entry.changeStatus(TimeEntryStatus.REJECTED);
        record(principal, entry, AdjustmentAction.REJECT, request.reason(), "status", old.name(), "REJECTED", now);
        log.info("Time entry {} rejected by admin {}", id, principal.employeeId());
        return id;
    }

    @Transactional
    public Long correct(EmployeePrincipal principal, Long id, ReviewRequests.Correct request) {
        TimeEntry entry = lockEntry(principal, id, request.expectedStatus());
        if (!entry.getEffectiveTime().truncatedTo(ChronoUnit.MICROS)
                .equals(request.expectedEffectiveTime().truncatedTo(ChronoUnit.MICROS))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ENTRY_CHANGED);
        }
        Long companyId = principal.companyId();
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        ZoneId zone = ZoneId.of(company.getTimeZone());
        Instant now = Instant.now(clock);
        Instant candidate = toInstant(request.localTime(), zone);

        TimeEntry pair = timeEntryRepository.findByCompanyIdAndEmployeeIdAndWorkDateAndType(companyId,
                entry.getEmployeeId(), entry.getWorkDate(), EntryDetailService.other(entry.getType())).orElse(null);
        if (!CorrectionRules.isValid(entry.getType(), entry.getWorkDate(), candidate, now, zone,
                pair == null ? null : pair.getEffectiveTime(), props.maxShiftDuration())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ENTRY_TIME);
        }
        if (candidate.truncatedTo(ChronoUnit.MINUTES).equals(entry.getEffectiveTime().truncatedTo(ChronoUnit.MINUTES))) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_CHANGE);
        }

        Instant oldEffective = entry.getEffectiveTime();
        Integer oldLate = entry.getLateMinutes();
        Integer newLate = oldLate;
        if (entry.getType() == TimeEntryType.CLOCK_IN) {
            // The current schedule of that weekday. Schedules are not versioned (documented in the plan).
            WorkSchedule schedule = workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId,
                    entry.getEmployeeId(), entry.getWorkDate().getDayOfWeek().getValue()).orElse(null);
            newLate = LatenessCalculator.lateMinutes(candidate, entry.getWorkDate(), zone, schedule);
        }
        TimeEntryStatus oldStatus = entry.getStatus();
        entry.correctEffectiveTime(candidate, newLate);
        record(principal, entry, AdjustmentAction.CORRECT_TIME, request.reason(), "effective_time",
                format(oldEffective, zone), format(candidate, zone), now);
        if (entry.getType() == TimeEntryType.CLOCK_IN && !Objects.equals(oldLate, newLate)) {
            record(principal, entry, AdjustmentAction.CORRECT_TIME, request.reason(), "late_minutes",
                    oldLate == null ? null : String.valueOf(oldLate), newLate == null ? "" : String.valueOf(newLate), now);
        }
        if (oldStatus != TimeEntryStatus.VALID) {
            // Correcting means the admin reviewed the entry.
            entry.changeStatus(TimeEntryStatus.VALID);
            record(principal, entry, AdjustmentAction.CORRECT_TIME, request.reason(), "status", oldStatus.name(),
                    "VALID", now);
        }
        log.info("Time entry {} corrected by admin {}", id, principal.employeeId());
        return id;
    }

    @Transactional
    public Long create(EmployeePrincipal principal, ReviewRequests.Create request) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        // Same lock as the PWA POST: an admin-created entry and the employee's own clocking never interleave.
        Employee employee = locks.lockActorAndTarget(companyId, principal.employeeId(), request.employeeId());
        if (employee.getBranchId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.NO_BRANCH_ASSIGNED);
        }
        Branch branch = branchRepository.findByIdAndCompanyId(employee.getBranchId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        ZoneId zone = ZoneId.of(company.getTimeZone());
        Instant now = Instant.now(clock);
        LocalDate workDate = request.workDate();

        if (timeEntryRepository.findByCompanyIdAndEmployeeIdAndWorkDateAndType(companyId, employee.getId(),
                workDate, request.type()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ENTRY_ALREADY_EXISTS);
        }
        TimeEntry pair = timeEntryRepository.findByCompanyIdAndEmployeeIdAndWorkDateAndType(companyId,
                employee.getId(), workDate, EntryDetailService.other(request.type())).orElse(null);
        if (request.type() == TimeEntryType.CLOCK_OUT && pair == null) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.CLOCK_IN_REQUIRED);
        }
        Instant candidate = toInstant(request.localTime(), zone);
        LocalDate localToday = now.atZone(zone).toLocalDate();
        if (workDate.isBefore(localToday.minusYears(MAX_YEARS_FROM_TODAY))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
        if (workDate.isAfter(localToday) || !CorrectionRules.isValid(request.type(), workDate,
                candidate, now, zone, pair == null ? null : pair.getEffectiveTime(), props.maxShiftDuration())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ENTRY_TIME);
        }

        Integer late = null;
        if (request.type() == TimeEntryType.CLOCK_IN) {
            WorkSchedule schedule = workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId,
                    employee.getId(), workDate.getDayOfWeek().getValue()).orElse(null);
            late = LatenessCalculator.lateMinutes(candidate, workDate, zone, schedule);
        }
        // server_time is when the admin acted; effective_time is the time that was typed. No evidence rows.
        TimeEntry saved = timeEntryRepository.save(TimeEntry.manual(companyId, employee.getId(), branch.getId(),
                request.type(), now, candidate, UUID.randomUUID(), workDate, late));
        record(principal, saved, AdjustmentAction.CREATE_ENTRY, request.reason(), "entry", null,
                request.type() + " " + format(candidate, zone), now);
        log.info("Time entry {} created by admin {} (source ADMIN)", saved.getId(), principal.employeeId());
        return saved.getId();
    }

    /** Actor and employee locks first, then the entry lock, then the expectedStatus check (409 ENTRY_CHANGED). */
    private TimeEntry lockEntry(EmployeePrincipal principal, Long id, TimeEntryStatus expectedStatus) {
        Long companyId = principal.companyId();
        locks.applyLockTimeout();
        Long employeeId = timeEntryRepository.findEmployeeIdByIdAndCompanyId(id, companyId).orElse(null);
        if (employeeId == null) {
            locks.lockActor(companyId, principal.employeeId());
            throw new ResourceNotFoundException();
        }
        locks.lockActorAndTarget(companyId, principal.employeeId(), employeeId);
        TimeEntry entry = timeEntryRepository.findWithLockByIdAndCompanyId(id, companyId)
                .orElseThrow(ResourceNotFoundException::new);
        if (entry.getStatus() != expectedStatus) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ENTRY_CHANGED);
        }
        return entry;
    }

    private void record(EmployeePrincipal principal, TimeEntry entry, AdjustmentAction action, String reason,
            String field, String oldValue, String newValue, Instant at) {
        adjustmentRepository.save(new Adjustment(principal.companyId(), entry.getId(), principal.employeeId(),
                action, reason, field, oldValue, newValue, at));
    }

    // The company zone decides what a typed time means; a DST overlap takes the earlier offset.
    static Instant toInstant(String localTime, ZoneId zone) {
        try {
            return LocalDateTime.parse(localTime).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
    }

    /** ISO instant with the company offset, e.g. 2026-10-07T17:00-06:00. */
    static String format(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime().toString();
    }
}
