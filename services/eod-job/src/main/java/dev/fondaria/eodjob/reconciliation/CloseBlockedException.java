package dev.fondaria.eodjob.reconciliation;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A step of the daily close was asked to run for a date whose internal reconciliation has not
 * passed.
 *
 * <p>Carries the date and what the gate found, and nothing else. The breaks themselves are in
 * {@code workflow.reconciliation_break}; an exception message is not where a list of drifted funds
 * belongs (AGENTS.md §7).
 */
public final class CloseBlockedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final LocalDate businessDate;

  public CloseBlockedException(LocalDate businessDate, String reason) {
    super("the daily close for " + businessDate + " is blocked: " + reason);
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
  }

  public LocalDate businessDate() {
    return businessDate;
  }
}
