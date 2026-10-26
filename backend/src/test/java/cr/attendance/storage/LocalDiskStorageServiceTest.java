package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

class LocalDiskStorageServiceTest {

    @TempDir
    Path temp;

    private LocalDiskStorageService service(Path root) {
        return new LocalDiskStorageService(new StorageProperties(root.toString(), DataSize.ofMegabytes(2), 2560,
                6_000_000, 1280, 0.85f, 3, java.time.Duration.ofSeconds(2), null));
    }

    private static List<Path> allFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void storeCreatesTheKeyedFileWithTheExactBytesAndNoTempFiles() throws IOException {
        Path root = temp.resolve("root");
        LocalDiskStorageService storage = service(root);
        byte[] bytes = {1, 2, 3, 4, 5};

        String key = storage.store(7, YearMonth.of(2026, 10), bytes);

        assertThat(key).matches("7/2026-10/[0-9a-f-]{36}\\.jpg");
        assertThat(Files.readAllBytes(root.toRealPath().resolve(key))).isEqualTo(bytes);
        assertThat(allFiles(root)).hasSize(1);
        assertThat(storage.load(7, key)).isPresent();
    }

    @Test
    void loadWithTheWrongCompanyIsEmpty() {
        LocalDiskStorageService storage = service(temp.resolve("root"));
        String key = storage.store(7, YearMonth.of(2026, 10), new byte[] {1});
        assertThat(storage.load(8, key)).isEmpty();
    }

    @Test
    void aTraversalKeyIsEmptyAndNeverTouchesFilesOutsideTheRoot() throws IOException {
        Path root = temp.resolve("root");
        Path outside = temp.resolve("outside.jpg");
        Files.write(outside, new byte[] {9});
        LocalDiskStorageService storage = service(root);

        assertThat(storage.load(1, "1/2026-10/../../../outside.jpg")).isEmpty();
        assertThat(storage.load(1, "../outside.jpg")).isEmpty();
        storage.delete("1/2026-10/../../../outside.jpg");
        storage.delete("../outside.jpg");
        assertThat(outside).exists();
    }

    @Test
    void aSymlinkInsideTheRootPointingOutsideIsEmpty() throws IOException {
        Path root = temp.resolve("root");
        LocalDiskStorageService storage = service(root);
        Path outside = temp.resolve("secret.jpg");
        Files.write(outside, new byte[] {9});
        String key = "5/2026-10/" + UUID.randomUUID() + ".jpg";
        Path link = root.toRealPath().resolve(key);
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "Symbolic links are not available here: " + e);
        }
        assertThat(storage.load(5, key)).isEmpty();
    }

    @Test
    void deleteIsIdempotent() {
        LocalDiskStorageService storage = service(temp.resolve("root"));
        String key = storage.store(3, YearMonth.of(2026, 10), new byte[] {1});
        Optional<Resource> before = storage.load(3, key);
        assertThat(before).isPresent();
        storage.delete(key);
        storage.delete(key);
        storage.delete("not a key");
        assertThat(storage.load(3, key)).isEmpty();
    }

    @Test
    void anUnwritableRootFailsAtConstruction() throws IOException {
        Path root = Files.createDirectory(temp.resolve("readonly"));
        boolean changed = root.toFile().setWritable(false);
        assumeTrue(changed && !Files.isWritable(root), "Cannot make a directory read-only here (Windows or root user)");
        try {
            assertThatThrownBy(() -> service(root)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a writable directory");
        } finally {
            root.toFile().setWritable(true);
        }
    }

    @Test
    void aRootThatIsAFileFailsAtConstruction() throws IOException {
        Path file = Files.createFile(temp.resolve("a-file"));
        assertThatThrownBy(() -> service(file)).isInstanceOf(IllegalStateException.class);
    }
}
