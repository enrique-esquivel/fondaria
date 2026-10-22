package dev.fondaria.valuationapi.ingestion;

import java.util.UUID;

/** No valuation batch exists under this id. */
public class ValuationBatchNotFoundException extends RuntimeException {

  private final UUID batchId;

  public ValuationBatchNotFoundException(UUID batchId) {
    super("Valuation batch not found: " + batchId);
    this.batchId = batchId;
  }

  public UUID batchId() {
    return batchId;
  }
}
