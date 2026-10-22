package dev.fondaria.valuationapi.ingestion;

import java.util.Objects;

/**
 * A whole-batch rejection, thrown by the parser or the validator and caught by {@link
 * ValuationIngestionRunner}, which records it on the batch.
 *
 * <p>Never rendered as an HTTP error. The submission that carried the file succeeded; the file
 * being wrong is an outcome read from the batch's status resource.
 */
public class ValuationRejectedException extends RuntimeException {

  private final transient ValuationRejection rejection;

  public ValuationRejectedException(ValuationRejection rejection) {
    super(rejection.code().name());
    this.rejection = Objects.requireNonNull(rejection, "rejection must not be null");
  }

  public ValuationRejection rejection() {
    return rejection;
  }
}
