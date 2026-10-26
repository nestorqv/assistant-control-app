package cr.attendance.timeentry;

import cr.attendance.common.ResourceNotFoundException;
import cr.attendance.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every miss (no entry, other tenant, other employee, no photo, no file) is the same 404. */
@Service
@Transactional(readOnly = true)
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final TimeEntryRepository timeEntryRepository;
    private final EvidenceRepository evidenceRepository;
    private final StorageService storage;

    public PhotoService(TimeEntryRepository timeEntryRepository, EvidenceRepository evidenceRepository,
            StorageService storage) {
        this.timeEntryRepository = timeEntryRepository;
        this.evidenceRepository = evidenceRepository;
        this.storage = storage;
    }

    /** employeeIdOrNull is set for the employee route (own photos only) and null for the admin route. */
    public Resource photoFor(long companyId, Long employeeIdOrNull, long timeEntryId) {
        TimeEntry entry = timeEntryRepository.findByIdAndCompanyId(timeEntryId, companyId)
                .orElseThrow(ResourceNotFoundException::new);
        if (employeeIdOrNull != null && !entry.getEmployeeId().equals(employeeIdOrNull)) {
            throw new ResourceNotFoundException();
        }
        String key = evidenceRepository.findByCompanyIdAndTimeEntryIdAndType(companyId, timeEntryId,
                EvidenceType.PHOTO).map(Evidence::getStorageKey).orElseThrow(ResourceNotFoundException::new);
        return storage.load(companyId, key).orElseThrow(() -> {
            log.warn("Photo file missing for time entry {}", timeEntryId);
            return new ResourceNotFoundException();
        });
    }

    /** Never cacheable and never public: the image is personal data. */
    public static ResponseEntity<Resource> toResponse(Resource photo) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(photo.contentLength())
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.inline().filename("photo.jpg").build().toString())
                    .body(photo);
        } catch (java.io.IOException e) {
            throw new ResourceNotFoundException();
        }
    }
}
