package cr.attendance.storage;

import java.util.Optional;

/** Identifies JPEG and PNG by magic bytes only. The client content type and filename are never used. */
public final class ImageFormatSniffer {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private ImageFormatSniffer() {
    }

    /** Returns "jpeg", "png" or empty. */
    public static Optional<String> sniff(byte[] head) {
        if (startsWith(head, JPEG)) {
            return Optional.of("jpeg");
        }
        if (startsWith(head, PNG)) {
            return Optional.of("png");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
