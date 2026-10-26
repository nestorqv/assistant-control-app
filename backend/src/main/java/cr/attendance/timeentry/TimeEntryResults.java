package cr.attendance.timeentry;

import cr.attendance.common.ApiException;
import cr.attendance.common.EmployeePrincipal;
import cr.attendance.common.ErrorCode;
import cr.attendance.company.BranchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Builds responses for new entries and for idempotent replays. Always scoped by company. */
@Component
public class TimeEntryResults {

    public record Result(TimeEntryResponse response, boolean replayed) {
    }

    private final BranchRepository branchRepository;
    private final EvidenceRepository evidenceRepository;

    public TimeEntryResults(BranchRepository branchRepository, EvidenceRepository evidenceRepository) {
        this.branchRepository = branchRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * An existing entry with the same key is a replay only for the same employee and the same type.
     * Anything else is a conflict, and nothing about the other entry is revealed.
     */
    public Result replayOrConflict(EmployeePrincipal principal, TimeEntryType type, TimeEntry existing) {
        if (!existing.getEmployeeId().equals(principal.employeeId()) || existing.getType() != type) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.CLIENT_UUID_CONFLICT);
        }
        Integer radius = branchRepository.findByIdAndCompanyId(existing.getBranchId(), existing.getCompanyId())
                .map(b -> b.getRadiusMeters()).orElse(null);
        boolean hasPhoto = evidenceRepository.findByCompanyIdAndTimeEntryIdAndType(existing.getCompanyId(),
                existing.getId(), EvidenceType.PHOTO).filter(e -> e.getStorageKey() != null).isPresent();
        return new Result(TimeEntryResponse.of(existing, radius, hasPhoto, true), true);
    }

    public Result created(TimeEntry entry, int radiusMeters) {
        return new Result(TimeEntryResponse.of(entry, radiusMeters, true, false), false);
    }
}
