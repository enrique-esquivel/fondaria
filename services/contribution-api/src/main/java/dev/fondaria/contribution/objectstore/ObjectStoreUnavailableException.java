package dev.fondaria.contribution.objectstore;

/**
 * The object store could not be reached, or refused a read for a reason that is not "no such
 * object".
 *
 * <p>Deliberately not a rejection. A batch whose file could not be read because of a fault on this
 * side stays claimable and is retried by the next validation pass; turning an outage into a {@code
 * MALFORMED_FILE} would tell an employer its file was wrong when it was not.
 */
public class ObjectStoreUnavailableException extends RuntimeException {

  public ObjectStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
