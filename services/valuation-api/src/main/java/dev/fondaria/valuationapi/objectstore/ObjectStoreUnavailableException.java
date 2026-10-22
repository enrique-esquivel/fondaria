package dev.fondaria.valuationapi.objectstore;

/**
 * The object store could not be reached, or refused a read for a reason that is not "no such
 * object".
 *
 * <p>Deliberately not a rejection. A batch whose file could not be read because of a fault on this
 * side stays claimable and is retried by the next validation pass. Turning an outage into a {@code
 * MALFORMED_FILE} would tell a provider its file was wrong when it was not — and {@code REJECTED}
 * is terminal, so the fund would stay unpriced until somebody noticed and asked for a redelivery.
 */
public class ObjectStoreUnavailableException extends RuntimeException {

  public ObjectStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
