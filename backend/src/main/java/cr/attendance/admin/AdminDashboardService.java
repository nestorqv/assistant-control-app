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
import cr.attendance.timeentry.AdjustmentRepository;
import cr.attendance.timeentry.Evidence;
import cr.attendance.timeentry.EvidenceRepository;
import cr.attendance.timeentry.EvidenceType;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryProperties;
import cr.attendance.timeentry.TimeEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily dashboard. Every query is batched and carries the companyId of the token. */
@Service
@Transactional(readOnly = true)
public class AdminDashboardService {

    private static final int MAX_YEARS_FROM_TODAY = 5;

    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final EmployeeRepository employeeRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final EvidenceRepository evidenceRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final TimeEntryProperties timeEntryProps;
    private final AdminProperties adminProps;
    private final Clock clock;

    public AdminDashboardService(CompanyRepository companyRepository, BranchRepository branchRepository,
            EmployeeRepository employeeRepository, WorkScheduleRepository workScheduleRepository,
            TimeEntryRepository timeEntryRepository, EvidenceRepository evidenceRepository,
            AdjustmentRepository adjustmentRepository, TimeEntryProperties timeEntryProps,
            AdminProperties adminProps, Clock clock) {
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.employeeRepository = employeeRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.evidenceRepository = evidenceRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.timeEntryProps = timeEntryProps;
        this.adminProps = adminProps;
        this.clock = clock;
    }

    public DashboardResponse dashboard(EmployeePrincipal principal, LocalDate dateOrNull, Long branchId) {
        Long companyId = principal.companyId();
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        ZoneId zone = ZoneId.of(company.getTimeZone());
        Instant now = Instant.now(clock);
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate date = dateOrNull != null ? dateOrNull : today;
        if (date.isBefore(today.minusYears(MAX_YEARS_FROM_TODAY))
                || date.isAfter(today.plusYears(MAX_YEARS_FROM_TODAY))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
        if (branchId != null) {
            branchRepository.findByIdAndCompanyId(branchId, companyId).orElseThrow(ResourceNotFoundException::new);
        }

        List<TimeEntry> entries = timeEntryRepository.findAllByCompanyIdAndWorkDate(companyId, date);
        Map<Long, Employee> employees = new LinkedHashMap<>();
        employeeRepository.findAllByCompanyIdAndActiveTrueAndBranchIdIsNotNull(companyId)
                .forEach(e -> employees.put(e.getId(), e));
        Set<Long> missing = new HashSet<>();
        for (TimeEntry e : entries) {
            if (!employees.containsKey(e.getEmployeeId())) {
                missing.add(e.getEmployeeId());
            }
        }
        if (!missing.isEmpty()) {
            employeeRepository.findAllByCompanyIdAndIdIn(companyId, missing)
                    .forEach(e -> employees.put(e.getId(), e));
        }

        Map<Long, WorkSchedule> schedules = workScheduleRepository
                .findAllByCompanyIdAndDayOfWeek(companyId, date.getDayOfWeek().getValue()).stream()
                .collect(Collectors.toMap(WorkSchedule::getEmployeeId, Function.identity(), (a, b) -> a));
        Set<Long> entryIds = entries.stream().map(TimeEntry::getId).collect(Collectors.toSet());
        Set<Long> photoIds = new HashSet<>();
        Set<Long> adjustedIds = new HashSet<>();
        if (!entryIds.isEmpty()) {
            evidenceRepository.findAllByCompanyIdAndTimeEntryIdInAndType(companyId, entryIds, EvidenceType.PHOTO)
                    .stream().filter(ev -> ev.getStorageKey() != null).map(Evidence::getTimeEntryId)
                    .forEach(photoIds::add);
            adjustedIds.addAll(adjustmentRepository.findTimeEntryIdsWithAdjustments(companyId, entryIds));
        }
        Map<Long, Branch> branches = branchRepository.findAllByCompanyIdOrderByName(companyId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));

        List<DashboardResponse.DashboardRow> rows = DayAssembler.assemble(new DayAssembler.Input(
                new ArrayList<>(employees.values()), schedules, entries, photoIds, adjustedIds, branches, date, now,
                zone, timeEntryProps.maxShiftDuration(), branchId));
        if (rows.size() > adminProps.maxDashboardRows()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.REPORT_TOO_LARGE);
        }
        return new DashboardResponse(date, today, company.getTimeZone(), company.getName(), branchId,
                KpiCalculator.compute(rows), rows, now);
    }
}
