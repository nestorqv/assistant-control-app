package cr.attendance.storage;

import java.time.YearMonth;
import java.util.Optional;
import org.springframework.core.io.Resource;

/** Photo storage. Knows nothing about time entries: it takes a tenant id, a month and bytes. */
public interface StorageService {

    /** Stores a JPEG and returns its key: {companyId}/{yyyy-MM}/{random uuid}.jpg. */
    String store(long companyId, YearMonth month, byte[] jpeg);

    /** Empty when the key is invalid, belongs to another company, escapes the root or the file is missing. */
    Optional<Resource> load(long companyId, String key);

    /** Idempotent. Invalid keys are ignored. */
    void delete(String key);
}
