package cr.attendance.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Builds photo bytes for tests. */
public final class TestImages {

    public static final String EXIF_MARKER = "SECRET-GPS-MARKER-9.9325,-84.0796";

    private TestImages() {
    }

    private static BufferedImage image(int width, int height, int type) {
        BufferedImage img = new BufferedImage(width, height, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(70, 130, 180, type == BufferedImage.TYPE_INT_ARGB ? 128 : 255));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.ORANGE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        return img;
    }

    private static byte[] write(BufferedImage img, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(img, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] jpeg(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    public static byte[] jpeg() {
        return jpeg(640, 480);
    }

    public static byte[] png(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_RGB), "png");
    }

    public static byte[] pngWithAlpha(int width, int height) {
        return write(image(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /** A valid JPEG with an APP1 "Exif" segment carrying EXIF_MARKER, inserted right after SOI. */
    public static byte[] jpegWithExif() {
        byte[] base = jpeg();
        byte[] payload = ("Exif\0\0" + EXIF_MARKER).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        byte[] segment = new byte[4 + payload.length];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        byte[] out = new byte[base.length + segment.length];
        System.arraycopy(base, 0, out, 0, 2);
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(base, 2, out, 2 + segment.length, base.length - 2);
        return out;
    }

    /** A PNG that is only a signature and an IHDR claiming huge dimensions: decoding it would exhaust memory. */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8; // bit depth
        ihdr[9] = 2; // RGB
        writeChunk(out, "IHDR", ihdr);
        return out.toByteArray();
    }

    public static byte[] text() {
        return "this is not an image".getBytes(StandardCharsets.UTF_8);
    }

    /** The JPEG magic bytes followed by garbage. */
    public static byte[] truncatedJpeg() {
        byte[] full = jpeg(800, 600);
        return Arrays.copyOf(full, full.length / 2);
    }

    /** Valid JPEG padded with trailing bytes until it is exactly this size. */
    public static byte[] jpegOfSize(int size) {
        byte[] base = jpeg();
        return Arrays.copyOf(base, size);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.writeBytes(crcBytes);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
