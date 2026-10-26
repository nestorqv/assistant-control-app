package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;

import cr.attendance.support.TestImages;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageFormatSnifferTest {

    @Test
    void jpegAndPngAreRecognised() {
        assertThat(ImageFormatSniffer.sniff(TestImages.jpeg())).contains("jpeg");
        assertThat(ImageFormatSniffer.sniff(TestImages.png(40, 40))).contains("png");
    }

    @Test
    void everythingElseIsRejected() {
        assertThat(ImageFormatSniffer.sniff("GIF89a......".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageFormatSniffer.sniff("RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageFormatSniffer.sniff("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageFormatSniffer.sniff(new byte[0])).isEmpty();
        assertThat(ImageFormatSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageFormatSniffer.sniff(null)).isEmpty();
    }
}
