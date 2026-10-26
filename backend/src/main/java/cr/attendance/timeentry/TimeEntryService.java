package cr.attendance.timeentry;

import cr.attendance.common.ApiException;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.employee.Employee;
import cr.attendance.employee.EmployeeRepository;
import cr.attendance.storage.PhotoProcessor;
import cr.attendance.storage.ProcessedPhoto;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a clock-in. Deliberately NOT transactional: the cheap lookups and the CPU-heavy photo work
 * run outside any lock, and only TimeEntryWriter holds a transaction.
 *
 * Order (first failure wins): replay lookup, photo present, consent and branch pre-check, photo
 * validation and re-encode, then the transaction.
 */
@Service
public class TimeEntryService {

    private static final Logger log = LoggerFactory.getLogger(TimeEntryService.class);

    private final TimeEntryRepository timeEntryRepository;
    private final EmployeeRepository employeeRepository;
    private final PhotoProcessor photoProcessor;
    private final TimeEntryWriter writer;
    private final TimeEntryResults results;

    public TimeEntryService(TimeEntryRepository timeEntryRepository, EmployeeRepository employeeRepository,
            PhotoProcessor photoProcessor, TimeEntryWriter writer, TimeEntryResults results) {
        this.timeEntryRepository = timeEntryRepository;
        this.employeeRepository = employeeRepository;
        this.photoProcessor = photoProcessor;
        this.writer = writer;
        this.results = results;
    }

    public TimeEntryResults.Result record(EmployeePrincipal principal, TimeEntryForm form) {
        Optional<TimeEntry> existing = timeEntryRepository.findByCompanyIdAndClientUuid(principal.companyId(),
                form.clientUuid());
        if (existing.isPresent()) {
            return results.replayOrConflict(principal, form.type(), existing.get());
        }
        if (form.photo() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.PHOTO_REQUIRED);
        }
        // Cheap read-only pre-check so an employee who cannot clock in never costs an image decode.
        Employee employee = employeeRepository.findByIdAndCompanyId(principal.employeeId(), principal.companyId())
                .orElseThrow(ResourceNotFoundException::new);
        TimeEntryWriter.requireConsentAndBranch(employee);

        ProcessedPhoto photo = photoProcessor.process(form.photo());
        try {
            return writer.record(principal, form, photo);
        } catch (DataIntegrityViolationException e) {
            return mapConstraintViolation(principal, form, e);
        }
    }

    // Backstop only: the employee row lock normally serializes duplicates before a constraint can fire.
    private TimeEntryResults.Result mapConstraintViolation(EmployeePrincipal principal, TimeEntryForm form,
            DataIntegrityViolationException e) {
        String cause = String.valueOf(e.getMostSpecificCause().getMessage());
        if (cause.contains("uq_time_entry_company_client_uuid")) {
            TimeEntry winner = timeEntryRepository.findByCompanyIdAndClientUuid(principal.companyId(),
                    form.clientUuid()).orElseThrow(() -> e);
            return results.replayOrConflict(principal, form.type(), winner);
        }
        if (cause.contains("uq_time_entry_employee_work_date_type")) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ALREADY_CLOCKED_IN);
        }
        log.error("Unexpected constraint violation while recording a time entry", e);
        throw e;
    }
}
