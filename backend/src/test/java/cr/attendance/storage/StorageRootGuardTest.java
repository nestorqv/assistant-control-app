package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Audit L-5: the photo root must never be somewhere the web server could publish. */
class StorageRootGuardTest {

    @TempDir
    Path temp;

    private static final Path CWD = Path.of("/work/backend").toAbsolutePath().normalize();

    private static void check(String root, List<String> staticLocations) {
        StorageRootGuard.check(Path.of(root).toAbsolutePath().normalize(), CWD, staticLocations);
    }

    @Test
    void sourceTreeAndStaticFoldersAreRefused() {
        for (String bad : new String[] {
            "/work/backend/src/main/resources/static/uploads",
            "/work/backend/src/main/resources/public",
            "/work/backend/src/main/resources",
            "/work/backend/src/main/webapp/photos",
            "/work/backend/target/classes/static",
            "/work/backend/target/classes/META-INF/resources/x",
            "/work/backend/static",
            "/work/backend/public/photos",
            "/opt/app/SRC/Main/Resources/Static",
        }) {
            assertThatThrownBy(() -> check(bad, List.of())).as(bad).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("static");
        }
    }

    @Test
    void ordinaryRootsAreAccepted() {
        for (String ok : new String[] {"/work/backend/uploads", "/var/lib/attendance/photos", "/data/static-photos",
            "/work/backend/target/test-uploads", "/work/other/static"}) {
            assertThatCode(() -> check(ok, List.of())).as(ok).doesNotThrowAnyException();
        }
    }

    @Test
    void configuredFileStaticLocationsAreRefusedButClasspathOnesAreIgnored() {
        List<String> locations = List.of("classpath:/static/", "file:/srv/www/", "file:///srv/assets");
        assertThatThrownBy(() -> check("/srv/www/photos", locations)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> check("/srv/assets", locations)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> check("/srv/photos", locations)).doesNotThrowAnyException();
    }

    @Test
    void theServiceRefusesToStartOnAStaticRootAndLogsNothingForIt() throws IOException {
        Path served = Files.createDirectories(temp.resolve("src/main/resources/static/uploads"));
        assertThatThrownBy(() -> new LocalDiskStorageService(props(served.toString(), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("static");
        assertThat(served.resolve(".upload-tmp")).doesNotExist();
    }

    @Test
    void aRelativeRootIsResolvedToAnAbsoluteNormalizedPath() throws IOException {
        Path relative = Path.of("target", "guard-test", "..", "guard-test", "photos");
        LocalDiskStorageService service = new LocalDiskStorageService(props(relative.toString(), null));
        String key = service.store(1, java.time.YearMonth.of(2026, 10), new byte[] {1});
        Path stored = service.load(1, key).orElseThrow().getFile().toPath();
        assertThat(stored).isAbsolute();
        assertThat(stored.toString()).doesNotContain("..");
        assertThat(service.tempDir()).isAbsolute();
        service.delete(key);
    }

    @Test
    void theTempDirIsCreatedInsideTheRootByDefaultAndStaleUploadsAreRemoved() throws IOException {
        Path root = temp.resolve("root");
        Path tmp = Files.createDirectories(root.resolve(".upload-tmp"));
        Path stale = Files.writeString(tmp.resolve("upload_123_00000000.tmp"), "raw bytes with GPS");
        Path other = Files.writeString(tmp.resolve("keep.txt"), "not an upload");

        LocalDiskStorageService service = new LocalDiskStorageService(props(root.toString(), null));

        assertThat(service.tempDir()).isEqualTo(root.toRealPath().resolve(".upload-tmp"));
        assertThat(stale).doesNotExist();
        assertThat(other).exists();
    }

    @Test
    void aConfiguredTempDirIsUsedAndPrivateWhereTheOsSupportsIt() throws IOException {
        Path custom = temp.resolve("spool");
        LocalDiskStorageService service = new LocalDiskStorageService(
                props(temp.resolve("root").toString(), custom.toString()));
        assertThat(service.tempDir()).isEqualTo(custom.toRealPath());
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(custom))).isEqualTo("rwx------");
        }
    }

    private static StorageProperties props(String root, String tempDir) {
        return new StorageProperties(root, DataSize.ofMegabytes(2), 2560, 6_000_000, 1280, 0.85f, 3,
                Duration.ofSeconds(2), tempDir);
    }
}
