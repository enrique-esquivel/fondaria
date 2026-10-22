package dev.fondaria.balanceapi.movement;

import java.io.Serial;

/**
 * A {@code projection.transfer} row on a participant holding account carries a transfer code that
 * is not one a holding account can post with.
 *
 * <p>A fault in our own data or in the chart of accounts, never in the caller's request — so it is
 * a {@code 500}, and it is raised rather than the row being dropped from the page. Carries only the
 * numeric code, which identifies nobody.
 */
public final class UnknownMovementTransferCodeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public UnknownMovementTransferCodeException(int ledgerCode) {
    super("transfer code " + ledgerCode + " cannot post to a participant holding account");
  }
}
