package dev.fondaria.contribution.fund.web;

import dev.fondaria.common.FundId;
import java.io.Serial;

/**
 * A {@code POST /v1/funds} replay named a {@code (fundId, version)} already published, but with
 * different content — the one case a replay is not a no-op (409).
 */
public final class FundVersionConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final FundId fundId;
  private final int version;

  public FundVersionConflictException(FundId fundId, int version) {
    super("Fund " + fundId + " version " + version + " already exists with different content");
    this.fundId = fundId;
    this.version = version;
  }

  public FundId fundId() {
    return fundId;
  }

  public int version() {
    return version;
  }
}
