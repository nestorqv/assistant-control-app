package cr.attendance.timeentry;

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
import cr.attendance.storage.ProcessedPhoto;
import cr.attendance.storage.StorageService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only transactional part of a clock-in. Takes a row lock on the employee so that all POSTs of one
 * employee are serialized, then applies consent, branch, sequence and review rules and writes the photo
 * and the rows. The server clock is the official time.
 */
@Service
public class TimeEntryWriter {

    private static final Logger log = LoggerFactory.getLogger(TimeEntryWriter.class);

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final WorkScheduleRepository workScheduleRepository;
    private final TimeEntryRepository timeEntryRepository;
    private final EvidenceRepository evidenceRepository;
    private final StorageService storage;
    private final TimeEntryResults results;
    private final TimeEntryProperties props;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public TimeEntryWriter(EmployeeRepository employeeRepository, CompanyRepository companyRepository,
            BranchRepository branchRepository, WorkScheduleRepository workScheduleRepository,
            TimeEntryRepository timeEntryRepository, EvidenceRepository evidenceRepository,
            StorageService storage, TimeEntryResults results, TimeEntryProperties props, Clock clock) {
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.workScheduleRepository = workScheduleRepository;
        this.timeEntryRepository = timeEntryRepository;
        this.evidenceRepository = evidenceRepository;
        this.storage = storage;
        this.results = results;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public TimeEntryResults.Result record(EmployeePrincipal principal, TimeEntryForm form, ProcessedPhoto photo) {
        Long companyId = principal.companyId();
        // Bound the row-lock wait in SQL (the Hibernate lock.timeout hint does nothing on PostgreSQL). A
        // timeout surfaces as a pessimistic-locking failure and becomes 503 SERVER_BUSY.
        entityManager.createNativeQuery("select set_config('lock_timeout', ?1, true)")
                .setParameter(1, props.lockTimeout().toMillis() + "ms").getSingleResult();
        Employee employee = employeeRepository.findWithLockByIdAndCompanyId(principal.employeeId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);

        // Re-check under the lock: a concurrent request with the same key may have just committed.
        Optional<TimeEntry> existing = timeEntryRepository.findByCompanyIdAndClientUuid(companyId,
                form.clientUuid());
        if (existing.isPresent()) {
            return results.replayOrConflict(principal, form.type(), existing.get());
        }

        requireConsentAndBranch(employee);
        Company company = companyRepository.findById(companyId).orElseThrow(ResourceNotFoundException::new);
        Branch branch = branchRepository.findByIdAndCompanyId(employee.getBranchId(), companyId)
                .orElseThrow(ResourceNotFoundException::new);

        // Never LocalDate.now() or the JVM zone: the company zone decides the work date.
        ZoneId zone = ZoneId.of(company.getTimeZone());
        Instant now = Instant.now(clock);
        LocalDate localToday = now.atZone(zone).toLocalDate();

        Duration maxShift = props.maxShiftDuration();
        List<TimeEntry> recent = timeEntryRepository
                .findAllByCompanyIdAndEmployeeIdAndServerTimeAfterOrderByServerTimeDesc(companyId,
                        employee.getId(), now.minus(lookback(maxShift)));
        ShiftResolver.Decision decision = ShiftResolver.decide(form.type(), recent, now, maxShift, localToday);
        if (decision.violation() != null) {
            throw new ApiException(HttpStatus.CONFLICT, decision.violation());
        }
        LocalDate workDate = decision.workDate();

        boolean hasGps = form.lat() != null && form.lng() != null;
        Integer distance = hasGps ? (int) Math.round(GeoDistance.meters(form.lat(), form.lng(),
                branch.getLatitude(), branch.getLongitude())) : null;
        Integer accuracy = form.accuracy() != null ? (int) Math.round(form.accuracy()) : null;

        ReviewPolicy.Outcome outcome = ReviewPolicy.evaluate(new ReviewPolicy.Input(form.lat(), form.lng(),
                form.accuracy(), distance, branch.getRadiusMeters(), now, form.deviceTime(), form.deviceSentAt(),
                decision.shiftTooLong()), props.clockSkewTolerance(), props.maxGpsAccuracyMeters());

        Integer lateMinutes = null;
        if (form.type() == TimeEntryType.CLOCK_IN) {
            WorkSchedule schedule = workScheduleRepository.findByCompanyIdAndEmployeeIdAndDayOfWeek(companyId,
                    employee.getId(), workDate.getDayOfWeek().getValue()).orElse(null);
            lateMinutes = LatenessCalculator.lateMinutes(now, workDate, zone, schedule);
        }

        String key = storage.store(companyId, YearMonth.from(now.atZone(zone)), photo.jpeg());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    storage.delete(key);
                } else if (status != STATUS_COMMITTED) {
                    // Outcome unknown: the row may exist, so keep the file. The Stage 5 sweeper decides.
                    log.warn("Commit outcome unknown, photo kept: {}", key);
                }
            }
        });

        TimeEntry saved = timeEntryRepository.save(new TimeEntry(companyId, employee.getId(), branch.getId(),
                form.type(), now, TimeEntrySource.MOBILE, form.clientUuid(), workDate)
                .withEvidence(form.deviceTime(), form.lat(), form.lng(), distance, accuracy, outcome.skewSeconds(),
                        lateMinutes, outcome.syncedLate(), outcome.status(), outcome.reasons()));
        evidenceRepository.save(new Evidence(companyId, saved.getId(), EvidenceType.PHOTO, key, now));
        if (hasGps) {
            evidenceRepository.save(new Evidence(companyId, saved.getId(), EvidenceType.GPS, null, now));
        }
        log.info("Time entry {} recorded: type={}, status={}, reasons={}", saved.getId(), saved.getType(),
                saved.getStatus(), saved.getReviewReasons());
        return results.created(saved, branch.getRadiusMeters());
    }

    /** How far back the sequence rules look: enough to cover a full local day and the shift window. */
    static Duration lookback(Duration maxShift) {
        Duration triple = maxShift.multipliedBy(3);
        return triple.compareTo(Duration.ofHours(48)) > 0 ? triple : Duration.ofHours(48);
    }

    static void requireConsentAndBranch(Employee employee) {
        if (employee.getConsentAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.CONSENT_REQUIRED);
        }
        if (employee.getBranchId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.NO_BRANCH_ASSIGNED);
        }
    }
}
