package dev.fondaria.contribution.batch;

import java.io.Serial;
import java.util.UUID;

/** No batch exists under this id for this organization. Rendered as a 404. */
public class ContributionBatchNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final UUID batchId;

  public ContributionBatchNotFoundException(UUID batchId) {
    super("No contribution batch " + batchId);
    this.batchId = batchId;
  }

  public UUID batchId() {
    return batchId;
  }
}
