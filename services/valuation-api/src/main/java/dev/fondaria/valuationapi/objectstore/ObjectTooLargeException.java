package dev.fondaria.valuationapi.objectstore;

/**
 * The stored object is larger than this service will read into memory.
 *
 * <p>Checked from the object's metadata before a byte is fetched. A valuation file is small by
 * nature — one row per fund per date, so tens of rows where a dispersion file has hundreds of
 * thousands — but the limit still has to exist, because the size is chosen by the caller and an
 * unbounded read of a caller-supplied object is a way to stop the service by uploading one file.
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
