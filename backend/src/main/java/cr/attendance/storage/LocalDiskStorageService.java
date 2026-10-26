package cr.attendance.storage;

import cr.attendance.common.ApiException;
import cr.attendance.common.ErrorCode;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Stores photos under app.storage.root, which is outside the web root. Keys are generated here and
 * validated on every access, so a client value can never become a path.
 */
@Service
public class LocalDiskStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskStorageService.class);
    private static final String DEFAULT_TEMP_DIR = ".upload-tmp";

    private final Path root;
    private final Path tempDir;

    public LocalDiskStorageService(StorageProperties props) {
        this(props, List.of());
    }

    @Autowired
    public LocalDiskStorageService(StorageProperties props, WebProperties web) {
        this(props, List.of(web.getResources().getStaticLocations()));
    }

    LocalDiskStorageService(StorageProperties props, List<String> staticLocations) {
        // Absolute and normalized, whatever the working directory is.
        Path configured = Paths.get(props.root()).toAbsolutePath().normalize();
        StorageRootGuard.check(configured, Paths.get("").toAbsolutePath().normalize(), staticLocations);
        try {
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
            StorageRootGuard.check(root, Paths.get("").toAbsolutePath().normalize(), staticLocations);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Photo storage root cannot be created: " + props.root(), e);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IllegalStateException("Photo storage root is not a writable directory: " + root);
        }
        this.tempDir = prepareTempDir(props);
        log.info("Photo storage root: {}", root);
        log.info("Upload temp directory: {}", tempDir);
    }

    /** Where multipart uploads are spooled. Private to the service user and emptied at every start. */
    public Path tempDir() {
        return tempDir;
    }

    private Path prepareTempDir(StorageProperties props) {
        Path dir = props.tempDir() != null && !props.tempDir().isBlank()
                ? Paths.get(props.tempDir()).toAbsolutePath().normalize()
                : root.resolve(DEFAULT_TEMP_DIR);
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
            // Raw uploads left by a crash still carry EXIF/GPS: remove them.
            try (DirectoryStream<Path> stale = Files.newDirectoryStream(dir, "upload_*")) {
                for (Path file : stale) {
                    Files.deleteIfExists(file);
                }
            }
            if (!Files.isWritable(dir)) {
                throw new IllegalStateException("Upload temp directory is not writable: " + dir);
            }
            return dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Upload temp directory cannot be prepared: " + dir, e);
        }
    }

    @Override
    public String store(long companyId, YearMonth month, byte[] jpeg) {
        String key = StorageKeys.newKey(companyId, month);
        Path target = root.resolve(key).normalize();
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, jpeg);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return key;
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort; the original failure is the one reported
                }
            }
            log.error("Photo could not be stored for company {}", companyId, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public Optional<Resource> load(long companyId, String key) {
        if (!StorageKeys.belongsTo(key, companyId)) {
            return Optional.empty();
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            return Optional.empty();
        }
        try {
            // toRealPath follows symlinks: a link inside the root that points outside is rejected here.
            Path real = path.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                return Optional.empty();
            }
            return Optional.of(new FileSystemResource(real));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        if (!StorageKeys.isValid(key)) {
            return;
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Photo file could not be deleted: {}", key, e);
        }
    }
}
