package dev.fondaria.contribution.batch;

import java.io.Serial;

/**
 * Thrown by the parser and the structural checks to abandon a batch at the first failed check.
 *
 * <p>Control flow rather than a fault: {@code contribution-file-v2.md} §4 fixes the order the
 * checks run in, and stopping at the first failure is what that order is for — a file with a bad
 * checksum is never parsed, and a truncated file's subtotals are never summed. Carrying the {@link
 * BatchRejection} means the reason reaches the batch row unchanged.
 */
public class BatchRejectedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final transient BatchRejection rejection;

  public BatchRejectedException(BatchRejection rejection) {
    super(rejection.code().name());
    this.rejection = rejection;
  }

  public BatchRejection rejection() {
    return rejection;
  }
}
