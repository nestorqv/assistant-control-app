package cr.attendance.admin;

import cr.attendance.common.ApiException;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.Constraints;
import cr.attendance.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Entry point of the review actions. Deliberately NOT transactional (same pattern as TimeEntryService): a
 * constraint violation surfaces at commit, which only happens after the ReviewWriter transaction has ended,
 * so it can be mapped here. The response is read after the commit.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewWriter writer;
    private final EntryDetailService detailService;

    public ReviewService(ReviewWriter writer, EntryDetailService detailService) {
        this.writer = writer;
        this.detailService = detailService;
    }

    public AdminEntryDetail approve(EmployeePrincipal principal, Long id, ReviewRequests.Action request) {
        return detailService.detail(principal, writer.approve(principal, id, request));
    }

    public AdminEntryDetail reject(EmployeePrincipal principal, Long id, ReviewRequests.Action request) {
        return detailService.detail(principal, writer.reject(principal, id, request));
    }

    public AdminEntryDetail correct(EmployeePrincipal principal, Long id, ReviewRequests.Correct request) {
        return detailService.detail(principal, writer.correct(principal, id, request));
    }

    public AdminEntryDetail create(EmployeePrincipal principal, ReviewRequests.Create request) {
        try {
            return detailService.detail(principal, writer.create(principal, request));
        } catch (DataIntegrityViolationException e) {
            String constraint = Constraints.nameOf(e);
            if ("uq_time_entry_employee_work_date_type".equals(constraint)) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ENTRY_ALREADY_EXISTS);
            }
            // Constraint name and class only: the driver message can carry row values (PII).
            log.error("Unexpected constraint violation while creating an admin entry constraint={} class={}",
                    constraint, e.getMostSpecificCause().getClass().getSimpleName());
            throw e;
        }
    }
}
