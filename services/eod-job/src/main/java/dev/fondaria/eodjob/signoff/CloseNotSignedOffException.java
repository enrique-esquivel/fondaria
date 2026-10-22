package dev.fondaria.eodjob.signoff;

import java.io.Serial;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something that may only run against a signed-off close was asked to run for a date that has no
 * {@code workflow.close_signoff} row.
 *
 * <p>The counterpart of {@code CloseBlockedException} (Task 7.2) and {@code
 * CustodianCloseBlockedException} (Task 7.3) for the final gate. Carries the date and nothing else
 * — why a date is not signed off is answered by which of the three upstream gates is closed, and
 * that is in the reconciliation, custodian and snapshot run tables, not in an exception message
 * (AGENTS.md §7).
 */
public final class CloseNotSignedOffException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final LocalDate businessDate;

  public CloseNotSignedOffException(LocalDate businessDate) {
    super("the daily close for " + businessDate + " has not been signed off");
    this.businessDate = Objects.requireNonNull(businessDate, "businessDate must not be null");
  }

  public LocalDate businessDate() {
    return businessDate;
  }
}
