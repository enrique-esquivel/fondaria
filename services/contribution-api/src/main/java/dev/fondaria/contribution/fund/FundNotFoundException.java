package dev.fondaria.contribution.fund;

import dev.fondaria.common.FundId;
import java.io.Serial;
import java.time.LocalDate;

/** No version of this fund resolves as of the date asked for. Carries only the opaque fund id. */
public final class FundNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final FundId fundId;

  public FundNotFoundException(FundId fundId, LocalDate asOfDate) {
    super("No fund is registered under id " + fundId + " as of " + asOfDate);
    this.fundId = fundId;
  }

  public FundId fundId() {
    return fundId;
  }
}
