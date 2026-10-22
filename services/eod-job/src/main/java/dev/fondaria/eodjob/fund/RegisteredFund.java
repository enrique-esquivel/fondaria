package dev.fondaria.eodjob.fund;

import dev.fondaria.common.FundId;
import java.util.Objects;

/**
 * A fund the reconciliation must tie: its identity and the ledger its units live on.
 *
 * <p>Read from {@code reference.fund_identity}, the immutable half of the registry, and not from
 * {@code reference.fund}, the versioned half. <b>A fund is reconciled because it exists, not
 * because it is currently open.</b> A {@code CLOSED} fund never releases its unit ledger (Task
 * 20.1's {@code FundStatus}), so units may still be held in it; leaving it out of the pass would
 * quietly stop tying the one book most likely to be forgotten.
 *
 * @param unitLedgerId the per-fund unit ledger id ({@code 1001+}), which is also what {@code
 *     projection.participant_units.fund_id} stores — the projection carries the ledger id, never
 *     the fund's UUID, because the ledger id is the only fund key on the CDC wire (Task 5.5)
 */
public record RegisteredFund(FundId fundId, long unitLedgerId) {

  public RegisteredFund {
    Objects.requireNonNull(fundId, "fundId must not be null");
    if (unitLedgerId <= 0) {
      throw new IllegalArgumentException("unitLedgerId must be positive");
    }
  }
}
