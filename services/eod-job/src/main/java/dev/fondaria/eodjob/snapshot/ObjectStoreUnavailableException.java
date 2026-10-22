package dev.fondaria.eodjob.snapshot;

/**
 * The object store could not be reached, or refused a write.
 *
 * <p>Ours, never the data's. The snapshot itself may have been perfectly correct; a pass that fails
 * here is recorded {@code FAILED} and re-run, and the re-run produces the identical file and stores
 * it. That is the whole benefit of the hash being a property of the book rather than of the pass.
 */
public class ObjectStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ObjectStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
