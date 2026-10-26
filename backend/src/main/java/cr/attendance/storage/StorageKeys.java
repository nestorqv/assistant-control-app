package cr.attendance.storage;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

public final class StorageKeys {

    // Same shape as the database CHECK (ck_evidence_storage_key_format), but with a strict UUID.
    private static final Pattern VALID = Pattern.compile(
            "^[0-9]{1,18}/[0-9]{4}-[0-9]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private StorageKeys() {
    }

    public static String newKey(long companyId, YearMonth month) {
        return companyId + "/" + MONTH.format(month) + "/" + UUID.randomUUID() + ".jpg";
    }

    public static boolean isValid(String key) {
        return key != null && VALID.matcher(key).matches();
    }

    public static boolean belongsTo(String key, long companyId) {
        return isValid(key) && key.startsWith(companyId + "/");
    }
}
