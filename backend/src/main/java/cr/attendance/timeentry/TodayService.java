package cr.attendance.timeentry;

import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.company.Branch;
import cr.attendance.company.BranchRepository;
import cr.attendance.company.Company;
import cr.attendance.company.CompanyRepository;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.employee.WorkSchedule;
import cr.attendance.employee.WorkScheduleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current shift state for the single clock button. "Today" is the company-local date, never UTC. */
@Service
@Transactional(readOnly = true)
public class TodayService {

    private final CompanyRepository companyRepository;
    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final EvidenceRepository evidenceRepository;
    private final TimeEntryProperties props;
    private final Clock clock;

    public TodayService(CompanyRepository companyRepository, EmployeeRepository employeeRepository,
            BranchRepository branchRepository, WorkScheduleRepository workScheduleRepository,
            TimeEntryRepository timeEntryRepository, EvidenceRepository evidenceRepository,
            TimeEntryProperties props, Clock clock) {
        this.companyRepository = companyRepository;
        this.employeeRepository = employeeRepository;
        this.branchRepository = branchRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.evidenceRepository = evidenceRepository;
        this.props = props;
        this.clock = clock;
    }

    public TodayResponse today(EmployeePrincipal principal) {
        Long companyId = principal.companyId();
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        Employee employee = employeeRepository.findByIdAndCompanyId(principal.employeeId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);

        ZoneId zone = ZoneId.of(company.getTimeZone());
        Instant now = Instant.now(clock);
        LocalDate localToday = now.atZone(zone).toLocalDate();
        Duration maxShift = props.maxShiftDuration();

        List<TimeEntry> recent = timeEntryRepository
                .findAllByCompanyIdAndEmployeeIdAndServerTimeAfterOrderByServerTimeDesc(companyId, employee.getId(),
                        now.minus(TimeEntryWriter.lookback(maxShift)));
        Optional<TimeEntry> open = ShiftResolver.openShift(recent, now, maxShift, localToday);
        Optional<TimeEntry> forgotten = ShiftResolver.forgottenShift(recent, now, maxShift, localToday);
        LocalDate workDate = open.map(TimeEntry::getWorkDate).orElse(localToday);

        List<TimeEntry> day = timeEntryRepository.findAllByCompanyIdAndEmployeeIdAndWorkDateOrderByServerTime(
                companyId, employee.getId(), workDate);
        TimeEntry clockIn = find(day, TimeEntryType.CLOCK_IN);
        TimeEntry clockOut = find(day, TimeEntryType.CLOCK_OUT);

        String blockedReason = null;
        if (employee.getConsentAt() == null) {
            blockedReason = "CONSENT_REQUIRED";
        } else if (employee.getBranchId() == null) {
            blockedReason = "NO_BRANCH_ASSIGNED";
        }
        NextAction nextAction = blockedReason != null ? NextAction.NONE
                : ShiftResolver.nextAction(recent, now, maxShift, localToday);

        Long workedMinutes = null;
        if (clockIn != null) {
            Instant end = clockOut != null ? clockOut.getServerTime() : now;
            workedMinutes = Duration.between(clockIn.getServerTime(), end).toMinutes();
        }
        boolean missingClockOut = forgotten.isPresent()
                || (open.isPresent() && !ShiftResolver.isFresh(open.get(), now, maxShift));

        TodayResponse.ScheduleInfo schedule = workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(
                companyId, employee.getId(), workDate.getDayOfWeek().getValue())
                .map(TodayService::scheduleInfo).orElse(null);
        TodayResponse.BranchInfo branchInfo = null;
        if (employee.getBranchId() != null) {
            branchInfo = branchRepository.findByIdAndCompanyId(employee.getBranchId(), companyId)
                    .map(TodayService::branchInfo).orElse(null);
        }

        return new TodayResponse(workDate, nextAction, blockedReason, summary(clockIn), summary(clockOut),
                workedMinutes, open.isPresent(), missingClockOut, forgotten.map(TimeEntry::getWorkDate).orElse(null),
                schedule, branchInfo, now, company.getTimeZone(), employee.getConsentAt() == null);
    }

    private TodayResponse.EntrySummary summary(TimeEntry entry) {
        if (entry == null) {
            return null;
        }
        boolean hasPhoto = evidenceRepository.findByCompanyIdAndTimeEntryIdAndType(entry.getCompanyId(),
                entry.getId(), EvidenceType.PHOTO).filter(e -> e.getStorageKey() != null).isPresent();
        return new TodayResponse.EntrySummary(entry.getId(), entry.getServerTime(), entry.getStatus(),
                entry.getReviewReasons(), entry.getDistanceMeters(), TimeEntryResponse.insideGeofence(entry),
                entry.getLateMinutes(), hasPhoto);
    }

    private static TimeEntry find(List<TimeEntry> day, TimeEntryType type) {
        return day.stream().filter(e -> e.getType() == type).findFirst().orElse(null);
    }

    private static TodayResponse.ScheduleInfo scheduleInfo(WorkSchedule s) {
        return new TodayResponse.ScheduleInfo(s.getStartTime(), s.getEndTime(), s.getGraceMinutes(),
                s.getEndTime().isBefore(s.getStartTime()));
    }

    private static TodayResponse.BranchInfo branchInfo(Branch b) {
        return new TodayResponse.BranchInfo(b.getName(), b.getRadiusMeters());
    }
}
