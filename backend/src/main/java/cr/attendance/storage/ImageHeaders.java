package cr.attendance.storage;

import java.util.Optional;

/**
 * Reads size and encoding straight from the file header, without ImageIO, so hostile files are refused
 * before any pixel is allocated. Only what the PWA produces is accepted: 8-bit baseline JPEG and
 * non-interlaced PNG up to 8 bits per channel.
 */
final class ImageHeaders {

    record Header(int width, int height) {
    }

    private ImageHeaders() {
    }

    /** Empty means "refuse this file". */
    static Optional<Header> inspect(byte[] data, String format) {
        return "png".equals(format) ? png(data) : jpeg(data);
    }

    private static Optional<Header> png(byte[] d) {
        // 8 signature bytes, then the IHDR chunk: length(4) "IHDR"(4) width(4) height(4) depth(1) colour(1)
        // compression(1) filter(1) interlace(1).
        if (d.length < 29 || d[12] != 'I' || d[13] != 'H' || d[14] != 'D' || d[15] != 'R') {
            return Optional.empty();
        }
        int bitDepth = d[24] & 0xFF;
        int interlace = d[28] & 0xFF;
        if (bitDepth > 8 || interlace != 0) {
            return Optional.empty(); // 16-bit rasters are 2x the memory; Adam7 costs extra passes
        }
        return Optional.of(new Header(int32(d, 16), int32(d, 20)));
    }

    private static Optional<Header> jpeg(byte[] d) {
        int i = 2; // after SOI
        while (i + 3 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                return Optional.empty();
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { // markers without a length
                i += 2;
                continue;
            }
            int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (marker == 0xC0 || marker == 0xC1) { // baseline / extended sequential, Huffman
                if (length < 8 || i + 9 >= d.length || (d[i + 4] & 0xFF) != 8) {
                    return Optional.empty();
                }
                int height = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                int width = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                return Optional.of(new Header(width, height));
            }
            boolean otherFrameType = marker >= 0xC2 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                    && marker != 0xCC; // progressive, lossless, arithmetic, hierarchical
            if (otherFrameType || marker == 0xDA || marker == 0xD9 || length < 2) {
                return Optional.empty();
            }
            i += 2 + length;
        }
        return Optional.empty();
    }

    private static int int32(byte[] d, int offset) {
        return ((d[offset] & 0xFF) << 24) | ((d[offset + 1] & 0xFF) << 16) | ((d[offset + 2] & 0xFF) << 8)
                | (d[offset + 3] & 0xFF);
    }
}
