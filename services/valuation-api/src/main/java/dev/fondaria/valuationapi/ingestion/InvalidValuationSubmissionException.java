package dev.fondaria.valuationapi.ingestion;

/**
 * The submission call itself is unusable — a malformed id, a checksum that is not a SHA-256 digest,
 * an object key belonging to another provider's prefix.
 *
 * <p>Distinct from {@link ValuationRejectedException}: this is a {@code 400} about the request,
 * raised before any batch exists. A rejected <i>file</i> is an outcome recorded on a batch that was
 * accepted for processing, and is never an HTTP error.
 */
public class InvalidValuationSubmissionException extends RuntimeException {

  public InvalidValuationSubmissionException(String message) {
    super(message);
  }
}
