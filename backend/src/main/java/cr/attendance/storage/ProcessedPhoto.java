package cr.attendance.storage;

/** A re-encoded JPEG without any metadata. */
public record ProcessedPhoto(byte[] jpeg, int width, int height) {
}
