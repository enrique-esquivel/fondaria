package dev.fondaria.eodjob.custodian;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A step downstream of custodian reconciliation was asked to run for a date whose custodian pass
 * has not passed. Carries the date and what the gate found, and nothing else — the breaks
 * themselves are in {@code workflow.custodian_break} (AGENTS.md §7).
 */
public final class CustodianCloseBlockedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final LocalDate businessDate;

  public CustodianCloseBlockedException(LocalDate businessDate, String reason) {
    super("custodian reconciliation for " + businessDate + " is blocked: " + reason);
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
  }

  public LocalDate businessDate() {
    return businessDate;
  }
}
