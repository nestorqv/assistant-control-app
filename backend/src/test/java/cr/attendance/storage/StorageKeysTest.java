package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageKeysTest {

    private static final String UUID_PART = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void generatedKeysAreValidAndBelongToTheCompany() {
        String key = StorageKeys.newKey(42, YearMonth.of(2026, 10));
        assertThat(key).startsWith("42/2026-10/").endsWith(".jpg");
        assertThat(StorageKeys.isValid(key)).isTrue();
        assertThat(StorageKeys.belongsTo(key, 42)).isTrue();
        assertThat(StorageKeys.belongsTo(key, 4)).isFalse();
        assertThat(StorageKeys.belongsTo(key, 421)).isFalse();
        assertThat(UUID.fromString(key.substring(11, key.length() - 4))).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "../1/2026-10/123e4567-e89b-12d3-a456-426614174000.jpg",
        "1/2026-10/../../x.jpg",
        "1\\2026-10\\123e4567-e89b-12d3-a456-426614174000.jpg",
        "/1/2026-10/123e4567-e89b-12d3-a456-426614174000.jpg",
        "C:/1/2026-10/123e4567-e89b-12d3-a456-426614174000.jpg",
        "1/2026-10/%2e%2e%2f123e4567-e89b-12d3-a456-426614174000.jpg",
        "1/2026-10/123E4567-E89B-12D3-A456-426614174000.jpg",
        "1/2026-10/extra/123e4567-e89b-12d3-a456-426614174000.jpg",
        "1/2026-10/123e4567-e89b-12d3-a456-426614174000.png",
        "1/2026-10/123e4567-e89b-12d3-a456-426614174000.jpg\n",
        "a/2026-10/123e4567-e89b-12d3-a456-426614174000.jpg",
        "1/2026-1/123e4567-e89b-12d3-a456-426614174000.jpg",
    })
    void malformedOrHostileKeysAreRejected(String key) {
        assertThat(StorageKeys.isValid(key)).isFalse();
    }

    @Test
    void nullAndWrongCompanyPrefix() {
        assertThat(StorageKeys.isValid(null)).isFalse();
        assertThat(StorageKeys.belongsTo(null, 1)).isFalse();
        assertThat(StorageKeys.belongsTo("2/2026-10/" + UUID_PART + ".jpg", 1)).isFalse();
    }
}
