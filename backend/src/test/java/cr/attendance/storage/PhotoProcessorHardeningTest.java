package cr.attendance.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cr.attendance.common.ApiException;
import cr.attendance.common.ErrorCode;
import cr.attendance.support.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/** Extra Stage 2 coverage: hostile bytes, real EXIF/GPS, polyglots, boundaries. Complements PhotoProcessorTest. */
class PhotoProcessorHardeningTest {

    private static final int TWO_MB = 2 * 1024 * 1024;

    private final PhotoProcessor processor = new PhotoProcessor(new StorageProperties("unused",
            DataSize.ofMegabytes(2), 2560, 6_000_000, 1280, 0.85f, 3, java.time.Duration.ofSeconds(2), null));

    private ProcessedPhoto process(byte[] bytes) {
        // Misleading name and type on purpose: only the bytes count.
        return processor.process(new MockMultipartFile("photo", "selfie.png", "image/png", bytes));
    }

    private void assertInvalid(String label, byte[] bytes) {
        assertThatThrownBy(() -> process(bytes)).as(label).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).as(label).isEqualTo(ErrorCode.INVALID_PHOTO));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
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

    /** Marker codes (second byte) of every segment before the scan data of a JPEG. */
    private static List<Integer> jpegMarkers(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        int i = 2;
        while (i + 3 < jpeg.length) {
            assertThat(jpeg[i]).as("segment start at " + i).isEqualTo((byte) 0xFF);
            int marker = jpeg[i + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xDA) { // start of scan: entropy-coded data follows
                break;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            i += 2 + length;
        }
        return markers;
    }

    private static byte[] insertAfterSoi(byte[] jpeg, byte[] segment) {
        return concat(Arrays.copyOfRange(jpeg, 0, 2), segment, Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, payload);
    }

    /** A real big-endian TIFF block: IFD0 -> GPS IFD with latitude, longitude and the refs. */
    private static byte[] exifWithGps() {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.writeBytes(ascii("MM"));
        t.writeBytes(new byte[] {0, 42, 0, 0, 0, 8});
        // IFD0: 1 entry, GPSInfo (0x8825) LONG count 1 -> GPS IFD at offset 26.
        t.writeBytes(new byte[] {0, 1, (byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 26, 0, 0, 0, 0});
        // GPS IFD at 26: 4 entries (12 bytes each) + next offset; data area after it at 26 + 2 + 48 + 4 = 80.
        t.writeBytes(new byte[] {0, 4});
        t.writeBytes(new byte[] {0, 1, 0, 2, 0, 0, 0, 2, 'N', 0, 0, 0});          // GPSLatitudeRef
        t.writeBytes(new byte[] {0, 2, 0, 5, 0, 0, 0, 3, 0, 0, 0, 80});           // GPSLatitude -> offset 80
        t.writeBytes(new byte[] {0, 3, 0, 2, 0, 0, 0, 2, 'W', 0, 0, 0});          // GPSLongitudeRef
        t.writeBytes(new byte[] {0, 4, 0, 5, 0, 0, 0, 3, 0, 0, 0, 104});          // GPSLongitude -> offset 104
        t.writeBytes(new byte[] {0, 0, 0, 0});
        for (int[] dms : new int[][] {{9, 55, 57}, {84, 4, 46}}) {
            for (int v : dms) {
                t.writeBytes(new byte[] {0, 0, 0, (byte) v, 0, 0, 0, 1});
            }
        }
        return concat(ascii("Exif\0\0"), t.toByteArray());
    }

    /** A well-formed single-chunk ICC payload (a real sRGB profile): phones do send these (Display P3). */
    private static byte[] iccPayload() {
        byte[] profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
        return concat(ascii("ICC_PROFILE\0"), new byte[] {1, 1}, profile);
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] t = ascii(type);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long c = crc.getValue();
        return concat(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16),
            (byte) (data.length >>> 8), (byte) data.length}, t, data,
                new byte[] {(byte) (c >>> 24), (byte) (c >>> 16), (byte) (c >>> 8), (byte) c});
    }

    @Test
    void realExifWithGpsTagsIsGoneAndOnlyImageSegmentsRemain() {
        byte[] input = insertAfterSoi(TestImages.jpeg(), segment(0xE1, exifWithGps()));
        assertThat(jpegMarkers(input)).contains(0xE1);

        ProcessedPhoto out = process(input);

        List<Integer> markers = jpegMarkers(out.jpeg());
        // APP1 (EXIF/XMP), APP2 (ICC), APP13 (IPTC) and COM must not survive.
        assertThat(markers).doesNotContain(0xE1, 0xE2, 0xED, 0xFE);
        assertThat(contains(out.jpeg(), ascii("Exif"))).isFalse();
        assertThat(contains(out.jpeg(), ascii("MM\0*"))).isFalse();
    }

    @Test
    void xmpIccIptcAndCommentSegmentsAreDropped() {
        byte[] base = TestImages.jpeg();
        byte[] input = base;
        input = insertAfterSoi(input, segment(0xFE, ascii("<script>alert(1)</script> TAGGED-COMMENT")));
        input = insertAfterSoi(input, segment(0xED, ascii("Photoshop 3.0\0 TAGGED-IPTC")));
        input = insertAfterSoi(input, segment(0xE2, iccPayload()));
        input = insertAfterSoi(input, segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0 TAGGED-XMP")));

        ProcessedPhoto out = process(input);

        assertThat(jpegMarkers(out.jpeg())).doesNotContain(0xE1, 0xE2, 0xED, 0xFE);
        assertThat(contains(out.jpeg(), ascii("TAGGED-"))).isFalse();
        assertThat(contains(out.jpeg(), ascii("<script>"))).isFalse();
        assertThat(contains(out.jpeg(), ascii("ICC_PROFILE"))).isFalse();
    }

    @Test
    void trailingPolyglotPayloadAfterEndOfImageIsNotCarriedOver() {
        byte[] payloads = concat(ascii("<html><script>alert(document.cookie)</script></html>"),
                new byte[] {'P', 'K', 3, 4}, ascii("<?php system($_GET['c']); ?>"));
        byte[] input = concat(TestImages.jpeg(), payloads);

        ProcessedPhoto out = process(input);

        assertThat(contains(out.jpeg(), ascii("<script>"))).isFalse();
        assertThat(contains(out.jpeg(), ascii("<?php"))).isFalse();
        assertThat(contains(out.jpeg(), new byte[] {'P', 'K', 3, 4})).isFalse();
        assertThat(out.jpeg()[out.jpeg().length - 2]).isEqualTo((byte) 0xFF);
        assertThat(out.jpeg()[out.jpeg().length - 1]).isEqualTo((byte) 0xD9);
    }

    @Test
    void pngTextAndExifChunksAreDropped() {
        byte[] png = TestImages.png(200, 200);
        // After the signature (8) and IHDR (4 + 4 + 13 + 4 = 25) insert tEXt, eXIf and iTXt chunks.
        int afterIhdr = 8 + 25;
        byte[] extra = concat(chunk("tEXt", ascii("Comment\0TAGGED-PNG-TEXT")),
                chunk("eXIf", exifWithGps()), chunk("iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0TAGGED-PNG-XMP")));
        byte[] input = concat(Arrays.copyOfRange(png, 0, afterIhdr), extra,
                Arrays.copyOfRange(png, afterIhdr, png.length));

        ProcessedPhoto out = process(input);

        assertThat(contains(out.jpeg(), ascii("TAGGED-"))).isFalse();
        assertThat(jpegMarkers(out.jpeg())).doesNotContain(0xE1, 0xE2, 0xED, 0xFE);
    }

    @Test
    void anImageWithATextHeadAfterSoiSegmentsStillIsRejectedWhenNotDecodable() {
        // SOI + a COM segment + garbage: the magic matches, the decode must fail cleanly.
        byte[] input = concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, segment(0xFE, ascii("hello")), ascii("garbage"));
        assertInvalid("magic ok, body garbage", input);
    }

    @Test
    void textMarkupAndOtherFormatsAreRejectedByTheirBytesWhateverTheClientSays() {
        assertInvalid("html", ascii("<!DOCTYPE html><html><script>alert(1)</script></html>"));
        assertInvalid("svg", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>"));
        assertInvalid("svg with xml prolog", ascii("<?xml version=\"1.0\"?><svg/>"));
        assertInvalid("php", ascii("<?php echo 1; ?>"));
        assertInvalid("pdf", ascii("%PDF-1.7\n1 0 obj"));
        assertInvalid("gif", concat(ascii("GIF89a"), new byte[64]));
        assertInvalid("webp", concat(ascii("RIFF"), new byte[] {0x24, 0, 0, 0}, ascii("WEBPVP8 "), new byte[64]));
        assertInvalid("bmp", concat(ascii("BM"), new byte[64]));
        assertInvalid("zip", concat(new byte[] {'P', 'K', 3, 4}, new byte[64]));
        assertInvalid("utf8 bom then html", concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                ascii("<html></html>")));
        assertInvalid("whitespace before a valid jpeg", concat(ascii("  "), TestImages.jpeg()));
        assertInvalid("a NUL byte before a valid png", concat(new byte[] {0}, TestImages.png(100, 100)));
    }

    @Test
    void signaturesWithoutAnImageBodyAreRejected() {
        assertInvalid("only the jpeg magic", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        assertInvalid("only soi", new byte[] {(byte) 0xFF, (byte) 0xD8});
        assertInvalid("one byte", new byte[] {(byte) 0xFF});
        assertInvalid("only the png signature",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] png = TestImages.png(200, 200);
        assertInvalid("png cut in the middle of the data", Arrays.copyOf(png, png.length / 2));
        assertInvalid("jpeg magic then a png body",
                concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, png));
    }

    @Test
    void aPngDeclaredAsJpegAndAJpegNamedPngBothWork() {
        // The helper already sends filename=selfie.png / image/png for every input.
        assertThat(process(TestImages.jpeg()).jpeg()[0]).isEqualTo((byte) 0xFF);
        ProcessedPhoto fromPng = processor.process(new MockMultipartFile("photo", "x.jpg", "image/jpeg",
                TestImages.png(200, 100)));
        assertThat(fromPng.width()).isEqualTo(200);
    }

    @Test
    void anInputOfExactlyTwoMegabytesIsNotTooLarge() {
        byte[] exact = TestImages.jpegOfSize(TWO_MB);
        assertThat(exact).hasSize(TWO_MB);
        try {
            process(exact);
        } catch (ApiException e) {
            // Trailing padding may be refused as a bad image, but never as "too large".
            assertThat(e.code()).isNotEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        }
    }

    @Test
    void dimensionBoundariesAreInclusive() throws IOException {
        assertThat(process(TestImages.jpeg(32, 32)).width()).isEqualTo(32);
        assertInvalid("31 px wide", TestImages.jpeg(31, 64));
        assertInvalid("31 px high", TestImages.jpeg(64, 31));
        // 2500 x 2400 = 6,000,000 pixels is exactly the cap.
        ProcessedPhoto max = process(TestImages.jpeg(2500, 2400));
        assertThat(max.width()).isEqualTo(1280);
        assertInvalid("6,000,001 pixels", TestImages.pngHeaderOnly(2500, 2401));
        assertInvalid("2561 px wide", TestImages.pngHeaderOnly(2561, 100));
        assertProcessed("2560 px wide is the side cap", TestImages.pngHeaderOnly(2560, 100));
    }

    @Test
    void progressiveJpegsAreRefusedAndBaselineGrayscaleIsAccepted() throws IOException {
        BufferedImage rgb = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().fillRect(0, 0, 200, 150);
        // The Stage 3 client produces baseline JPEG; progressive costs one decode pass per scan.
        assertInvalid("progressive JPEG", encodeJpeg(rgb, true));

        BufferedImage gray = new BufferedImage(400, 300, BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().fillRect(0, 0, 200, 150);
        ProcessedPhoto fromGray = process(encodeJpeg(gray, false));
        assertThat(ImageIO.read(new ByteArrayInputStream(fromGray.jpeg()))).isNotNull();
    }

    /** The header passes (dimensions are fine); the body is only a header, so the decode itself must fail. */
    private void assertProcessed(String label, byte[] bytes) {
        try {
            process(bytes);
        } catch (ApiException e) {
            assertThat(e.code()).as(label).isEqualTo(ErrorCode.INVALID_PHOTO);
        }
    }

    @Test
    void sixteenBitAndInterlacedPngsAreRefusedBeforeAnyDecode() throws IOException {
        BufferedImage deep = new BufferedImage(2500, 2400, BufferedImage.TYPE_USHORT_GRAY);
        long start = System.nanoTime();
        assertInvalid("16-bit PNG at the pixel cap", encodePng(deep, false));
        BufferedImage plain = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        assertInvalid("interlaced PNG", encodePng(plain, true));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
        // The same sizes as plain 8-bit, non-interlaced PNG are fine.
        assertThat(process(encodePng(plain, false)).width()).isEqualTo(600);
    }

    @Test
    void arithmeticAndLosslessJpegFrameTypesAreRefused() throws IOException {
        byte[] baseline = TestImages.jpeg(200, 200);
        for (int sof : new int[] {0xC2, 0xC3, 0xC9, 0xCA}) {
            byte[] patched = baseline.clone();
            for (int i = 2; i < patched.length - 1; i++) {
                if ((patched[i] & 0xFF) == 0xFF && (patched[i + 1] & 0xFF) == 0xC0) {
                    patched[i + 1] = (byte) sof;
                    break;
                }
            }
            assertInvalid("SOF" + Integer.toHexString(sof), patched);
        }
    }

    private static byte[] encodePng(BufferedImage image, boolean interlaced) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(interlaced ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, boolean progressive) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (progressive) {
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
