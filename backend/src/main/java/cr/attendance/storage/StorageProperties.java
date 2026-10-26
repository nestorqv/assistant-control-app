package cr.attendance.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * maxConcurrentDecodes bounds how many photos are decoded at once in the whole process; a request that
 * cannot get a slot within decodeAcquireTimeout gets 503 SERVER_BUSY. tempDir (optional) is where
 * multipart uploads are spooled; the default is a hidden folder inside the storage root.
 */
@ConfigurationProperties("app.storage")
@Validated
public record StorageProperties(
        @NotBlank String root,
        DataSize maxPhotoBytes,
        @Positive int maxInputDimension,
        @Positive long maxInputPixels,
        @Positive int outputMaxDimension,
        @Positive float jpegQuality,
        @Positive int maxConcurrentDecodes,
        Duration decodeAcquireTimeout,
        String tempDir) {
}
