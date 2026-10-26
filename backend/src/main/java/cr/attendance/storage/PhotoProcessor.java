package cr.attendance.storage;

import cr.attendance.common.ApiException;
import cr.attendance.common.ErrorCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates an uploaded photo and re-encodes it to a JPEG without metadata. Re-encoding drops EXIF
 * (including GPS), ICC profiles, comments and any payload hidden after the image data.
 */
@Component
public class PhotoProcessor {

    private static final int MIN_DIMENSION = 32;

    private final StorageProperties props;
    // Process-wide cap on simultaneous decodes: each one can hold tens of MB of raster.
    private final Semaphore decodePermits;

    public PhotoProcessor(StorageProperties props) {
        this.props = props;
        this.decodePermits = new Semaphore(props.maxConcurrentDecodes(), true);
        // No ImageIO temp files on disk.
        ImageIO.setUseCache(false);
    }

    /** The "photo required" check belongs to the caller; a null part here is a programming error. */
    public ProcessedPhoto process(MultipartFile photo) {
        if (photo == null) {
            throw new IllegalArgumentException("photo must not be null");
        }
        long maxBytes = props.maxPhotoBytes().toBytes();
        if (photo.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = photo.getBytes();
        } catch (IOException e) {
            throw invalid();
        }
        if (bytes.length == 0) {
            throw invalid();
        }
        if (bytes.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
        }
        String format = ImageFormatSniffer.sniff(bytes).orElseThrow(PhotoProcessor::invalid);
        // Header-only checks: size limits, 16-bit or interlaced PNG, progressive JPEG. No pixels yet.
        ImageHeaders.Header header = ImageHeaders.inspect(bytes, format).orElseThrow(PhotoProcessor::invalid);
        if (header.width() < MIN_DIMENSION || header.height() < MIN_DIMENSION
                || header.width() > props.maxInputDimension() || header.height() > props.maxInputDimension()
                || (long) header.width() * header.height() > props.maxInputPixels()) {
            throw invalid();
        }
        acquireDecodeSlot();
        try {
            return encode(decode(bytes, format));
        } finally {
            decodePermits.release();
        }
    }

    /** For tests: the slots that bound simultaneous decodes. */
    Semaphore decodePermits() {
        return decodePermits;
    }

    private void acquireDecodeSlot() {
        try {
            if (!decodePermits.tryAcquire(props.decodeAcquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVER_BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVER_BUSY);
        }
    }

    private BufferedImage decode(byte[] bytes, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw invalid();
        }
        ImageReader reader = readers.next();
        AtomicBoolean warned = new AtomicBoolean();
        reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            // seekForwardOnly=true, ignoreMetadata=true.
            reader.setInput(in, true, true);
            BufferedImage image = reader.read(0);
            // A truncated JPEG decodes with a warning instead of an error.
            if (image == null || warned.get()) {
                throw invalid();
            }
            return image;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid();
        } finally {
            reader.dispose();
        }
    }

    private ProcessedPhoto encode(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longSide = Math.max(width, height);
        if (longSide > props.outputMaxDimension()) {
            double scale = (double) props.outputMaxDimension() / longSide;
            width = Math.max(1, (int) Math.round(width * scale));
            height = Math.max(1, (int) Math.round(height * scale));
        }
        // White RGB canvas: drops alpha (the JDK JPEG writer rejects it) and flattens transparency to white.
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(props.jpegQuality());
            writer.setOutput(ios);
            // Null metadata: nothing from the upload is carried over.
            writer.write(null, new IIOImage(canvas, null, null), param);
            ios.flush();
            return new ProcessedPhoto(out.toByteArray(), width, height);
        } catch (IOException e) {
            throw invalid();
        } finally {
            writer.dispose();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_PHOTO);
    }
}
