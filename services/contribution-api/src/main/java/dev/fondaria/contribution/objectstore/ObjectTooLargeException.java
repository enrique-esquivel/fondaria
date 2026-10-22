package dev.fondaria.contribution.objectstore;

/**
 * The stored object is larger than this service will read into memory.
 *
 * <p>Checked from the object's metadata before a byte is fetched. A dispersion file can
 * legitimately run to hundreds of thousands of rows (ADR-0009), so the limit is generous — but it
 * has to exist, because an unbounded read of a caller-supplied object is a way to stop the service
 * by uploading one file.
 */
public class ObjectTooLargeException extends RuntimeException {

  private final long sizeBytes;
  private final long maximumBytes;

  public ObjectTooLargeException(long sizeBytes, long maximumBytes) {
    super("Stored object is " + sizeBytes + " bytes, above the maximum of " + maximumBytes);
    this.sizeBytes = sizeBytes;
    this.maximumBytes = maximumBytes;
  }

  public long sizeBytes() {
    return sizeBytes;
  }

  public long maximumBytes() {
    return maximumBytes;
  }
}
