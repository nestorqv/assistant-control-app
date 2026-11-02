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
import cr.attendance.timeentry.Adjustment;
import cr.attendance.timeentry.AdjustmentRepository;
import cr.attendance.timeentry.Evidence;
import cr.attendance.timeentry.EvidenceRepository;
import cr.attendance.timeentry.EvidenceType;
import cr.attendance.timeentry.TimeEntry;
import cr.attendance.timeentry.TimeEntryRepository;
import cr.attendance.timeentry.TimeEntryStatus;
import cr.attendance.timeentry.TimeEntryType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the review flow: the single-entry detail, the paged queue and the pending count. */
@Service
@Transactional(readOnly = true)
public class EntryDetailService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 25;
    private static final LocalDate NO_LOWER_BOUND = LocalDate.of(2000, 1, 1);
    private static final int REJECTED_DEFAULT_DAYS = 31;

    private final TimeEntryRepository timeEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final BranchRepository branchRepository;
    private final CompanyRepository companyRepository;
    private final EvidenceRepository evidenceRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final Clock clock;

    public EntryDetailService(TimeEntryRepository timeEntryRepository, EmployeeRepository employeeRepository,
            BranchRepository branchRepository, CompanyRepository companyRepository,
            EvidenceRepository evidenceRepository, AdjustmentRepository adjustmentRepository, Clock clock) {
        this.timeEntryRepository = timeEntryRepository;
        this.employeeRepository = employeeRepository;
        this.branchRepository = branchRepository;
        this.companyRepository = companyRepository;
        this.evidenceRepository = evidenceRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.clock = clock;
    }

    public AdminEntryDetail detail(EmployeePrincipal principal, Long id) {
        Long companyId = principal.companyId();
        TimeEntry entry = timeEntryRepository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(ResourceNotFoundException::new);
        Employee employee = employeeRepository.findByIdAndCompanyId(entry.getEmployeeId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);
        Branch branch = branchRepository.findByIdAndCompanyId(entry.getBranchId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);

        List<Adjustment> adjustments = adjustmentRepository
                .findAllByCompanyIdAndTimeEntryIdOrderByCreatedAtAscIdAsc(companyId, id);
        Set<Long> adminIds = adjustments.stream().map(Adjustment::getAdminId).collect(Collectors.toSet());
        Map<Long, String> adminNames = new HashMap<>();
        if (!adminIds.isEmpty()) {
            employeeRepository.findAllByCompanyIdAndIdIn(companyId, adminIds)
                    .forEach(a -> adminNames.put(a.getId(), a.getName()));
        }
        List<AdminEntryDetail.AdjustmentView> history = adjustments.stream()
                .map(a -> new AdminEntryDetail.AdjustmentView(a.getId(), a.getAction(), a.getField(),
                        a.getOldValue(), a.getNewValue(), a.getReason(), adminNames.getOrDefault(a.getAdminId(), ""),
                        a.getAdminId().equals(entry.getEmployeeId()), a.getCreatedAt()))
                .toList();

        TimeEntry pair = timeEntryRepository.findByCompanyIdAndEmployeeIdAndWorkDateAndType(companyId,
                entry.getEmployeeId(), entry.getWorkDate(), other(entry.getType())).orElse(null);
        Set<Long> ids = pair == null ? Set.of(id) : Set.of(id, pair.getId());
        Set<Long> photos = photoIds(companyId, ids);
        Set<Long> adjusted = ids.isEmpty() ? Set.of() : new HashSet<>(
                adjustmentRepository.findTimeEntryIdsWithAdjustments(companyId, ids));

        AdminEntryDetail.Location location = entry.getLatitude() == null ? null
                : new AdminEntryDetail.Location(entry.getLatitude(), entry.getLongitude());
        return new AdminEntryDetail(entry.getId(), entry.getType(), entry.getWorkDate(),
                new AdminEntryDetail.Employee(employee.getId(), employee.getCode(), employee.getName(),
                        employee.getJobTitle()),
                employee.getId().equals(principal.employeeId()), entry.getSource(), entry.getServerTime(),
                entry.getEffectiveTime(), entry.getDeviceTime(), entry.getSkewSeconds(), entry.isSyncedLate(),
                entry.getStatus(), entry.getReviewReasons(), entry.getDistanceMeters(),
                entry.getGpsAccuracyMeters(), cr.attendance.timeentry.TimeEntryResponse.insideGeofence(entry),
                entry.getLateMinutes(), photos.contains(id), entry.getLatitude() != null, !history.isEmpty(),
                location,
                new AdminEntryDetail.Branch(branch.getId(), branch.getName(), branch.getLatitude(),
                        branch.getLongitude(), branch.getRadiusMeters()),
                pair == null ? null : AdminEntrySummary.of(pair, photos.contains(pair.getId()),
                        adjusted.contains(pair.getId())),
                history);
    }

    public ReviewItem.Page queue(EmployeePrincipal principal, TimeEntryStatus statusOrNull, LocalDate from,
            LocalDate to, Long employeeId, Integer pageOrNull, Integer sizeOrNull) {
        Long companyId = principal.companyId();
        TimeEntryStatus status = statusOrNull != null ? statusOrNull : TimeEntryStatus.PENDING_REVIEW;
        if (status == TimeEntryStatus.VALID) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
        int page = pageOrNull != null ? pageOrNull : 0;
        int size = sizeOrNull != null ? sizeOrNull : DEFAULT_PAGE_SIZE;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
        }
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        LocalDate today = Instant.now(clock).atZone(ZoneId.of(company.getTimeZone())).toLocalDate();
        // Pending entries have no lower bound by default so the nav badge and the list always agree.
        LocalDate lower = from != null ? from : status == TimeEntryStatus.PENDING_REVIEW ? NO_LOWER_BOUND
                : today.minusDays(REJECTED_DEFAULT_DAYS);
        LocalDate upper = to != null ? to : today;
        for (LocalDate bound : new LocalDate[] {from, to}) {
            if (bound != null && (bound.isBefore(today.minusYears(5)) || bound.isAfter(today))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST);
            }
        }
        PageRequest pageable = PageRequest.of(page, size);
        Page<TimeEntry> result = employeeId == null
                ? timeEntryRepository.findReviewPage(companyId, status, lower, upper, pageable)
                : timeEntryRepository.findReviewPageForEmployee(companyId, status, employeeId, lower, upper,
                        pageable);
        return new ReviewItem.Page(items(principal, result.getContent()), page, size, result.getTotalElements());
    }

    public ReviewItem.Count count(EmployeePrincipal principal) {
        Long companyId = principal.companyId();
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        LocalDate today = Instant.now(clock).atZone(ZoneId.of(company.getTimeZone())).toLocalDate();
        return new ReviewItem.Count(timeEntryRepository.countByCompanyIdAndStatus(companyId,
                TimeEntryStatus.PENDING_REVIEW), today, company.getTimeZone());
    }

    private List<ReviewItem> items(EmployeePrincipal principal, List<TimeEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Long companyId = principal.companyId();
        Set<Long> employeeIds = entries.stream().map(TimeEntry::getEmployeeId).collect(Collectors.toSet());
        Set<LocalDate> workDates = entries.stream().map(TimeEntry::getWorkDate).collect(Collectors.toSet());
        Map<Long, Employee> employees = employeeRepository.findAllByCompanyIdAndIdIn(companyId, employeeIds)
                .stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
        List<TimeEntry> sameDays = timeEntryRepository.findAllByCompanyIdAndEmployeeIdInAndWorkDateIn(companyId,
                employeeIds, workDates);
        Set<Long> ids = new HashSet<>(sameDays.stream().map(TimeEntry::getId).toList());
        entries.forEach(e -> ids.add(e.getId()));
        Set<Long> photos = photoIds(companyId, ids);
        Set<Long> adjusted = new HashSet<>(adjustmentRepository.findTimeEntryIdsWithAdjustments(companyId, ids));

        return entries.stream().map(e -> {
            Employee emp = employees.get(e.getEmployeeId());
            TimeEntry pair = sameDays.stream()
                    .filter(p -> p.getEmployeeId().equals(e.getEmployeeId()) && p.getWorkDate().equals(e.getWorkDate())
                            && p.getType() == other(e.getType()))
                    .findFirst().orElse(null);
            return new ReviewItem(
                    new ReviewItem.Employee(emp.getId(), emp.getCode(), emp.getName(), emp.getJobTitle()),
                    e.getWorkDate(), emp.getId().equals(principal.employeeId()),
                    new ReviewItem.Entry(e.getId(), e.getType(), e.getEffectiveTime(), e.getServerTime(),
                            e.getStatus(), e.getReviewReasons(), e.getSource(), e.getDistanceMeters(),
                            cr.attendance.timeentry.TimeEntryResponse.insideGeofence(e), photos.contains(e.getId()),
                            e.getLatitude() != null, adjusted.contains(e.getId()), e.getDeviceTime(),
                            e.getSkewSeconds(), e.isSyncedLate(), e.getGpsAccuracyMeters(), e.getLateMinutes()),
                    pair == null ? null : AdminEntrySummary.of(pair, photos.contains(pair.getId()),
                            adjusted.contains(pair.getId())));
        }).toList();
    }

    private Set<Long> photoIds(Long companyId, Set<Long> entryIds) {
        if (entryIds.isEmpty()) {
            return Set.of();
        }
        return evidenceRepository.findAllByCompanyIdAndTimeEntryIdInAndType(companyId, entryIds, EvidenceType.PHOTO)
                .stream().filter(ev -> ev.getStorageKey() != null).map(Evidence::getTimeEntryId)
                .collect(Collectors.toSet());
    }

    static TimeEntryType other(TimeEntryType type) {
        return type == TimeEntryType.CLOCK_IN ? TimeEntryType.CLOCK_OUT : TimeEntryType.CLOCK_IN;
    }
}
