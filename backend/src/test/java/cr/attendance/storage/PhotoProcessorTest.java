package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.common.ApiException;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class PhotoProcessorTest {

    private final PhotoProcessor processor = new PhotoProcessor(new StorageProperties("unused",
            DataSize.ofMegabytes(2), 2560, 6_000_000, 1280, 0.85f, 3, java.time.Duration.ofSeconds(2), null));

    private static MockMultipartFile part(byte[] bytes) {
        // The client filename and content type are deliberately misleading: only the bytes count.
        return new MockMultipartFile("photo", "../../evil.php", "text/html", bytes);
    }

    private static void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> new PhotoProcessorTest().processor.process(part(bytes)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PHOTO);
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void jpegAndPngAreAcceptedAndReencodedAsJpeg() {
        for (byte[] input : new byte[][] {TestImages.jpeg(), TestImages.png(300, 200)}) {
            ProcessedPhoto out = processor.process(part(input));
            assertThat(out.jpeg()[0]).isEqualTo((byte) 0xFF);
            assertThat(out.jpeg()[1]).isEqualTo((byte) 0xD8);
            assertThat(out.jpeg()[2]).isEqualTo((byte) 0xFF);
        }
    }

    @Test
    void exifIsStripped() {
        byte[] input = TestImages.jpegWithExif();
        assertThat(contains(input, TestImages.EXIF_MARKER.getBytes(StandardCharsets.ISO_8859_1))).isTrue();

        ProcessedPhoto out = processor.process(part(input));

        assertThat(contains(out.jpeg(), TestImages.EXIF_MARKER.getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(contains(out.jpeg(), "Exif".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(contains(out.jpeg(), new byte[] {(byte) 0xFF, (byte) 0xE1})).isFalse();
    }

    @Test
    void alphaPngBecomesAnRgbJpeg() throws IOException {
        ProcessedPhoto out = processor.process(part(TestImages.pngWithAlpha(200, 200)));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.jpeg()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    void largeImagesAreDownscaledToTheConfiguredLongSide() throws IOException {
        ProcessedPhoto out = processor.process(part(TestImages.jpeg(2400, 1600)));
        assertThat(out.width()).isEqualTo(1280);
        assertThat(out.height()).isEqualTo(853);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.jpeg()));
        assertThat(decoded.getWidth()).isEqualTo(1280);
    }

    @Test
    void smallImagesAreNotUpscaled() {
        ProcessedPhoto out = processor.process(part(TestImages.jpeg(640, 480)));
        assertThat(out.width()).isEqualTo(640);
        assertThat(out.height()).isEqualTo(480);
    }

    @Test
    void aHugeHeaderIsRejectedWithoutDecoding() {
        byte[] bomb = TestImages.pngHeaderOnly(50_000, 50_000);
        long start = System.nanoTime();
        assertInvalid(bomb);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void tinyAndOversizedDimensionsAreRejected() {
        assertInvalid(TestImages.jpeg(16, 16));
        assertInvalid(TestImages.pngHeaderOnly(5000, 100));
    }

    @Test
    void notAnImageTruncatedAndEmptyAreInvalid() {
        assertInvalid(TestImages.text());
        assertInvalid(TestImages.truncatedJpeg());
        assertInvalid(new byte[0]);
        assertInvalid("GIF89a".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void twoMegabytesPlusOneByteIsTooLarge() {
        byte[] tooBig = TestImages.jpegOfSize(2 * 1024 * 1024 + 1);
        assertThatThrownBy(() -> processor.process(part(tooBig)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                });
    }

    @Test
    void aNullPartIsAProgrammingError() {
        assertThatThrownBy(() -> processor.process(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
