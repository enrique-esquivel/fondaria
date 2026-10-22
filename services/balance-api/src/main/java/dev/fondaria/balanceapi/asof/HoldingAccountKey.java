package dev.fondaria.balanceapi.asof;

import java.util.Objects;

/**
 * One derived holding account, and the unit ledger its fund issues on — the pair {@code
 * projection.participant_units} is keyed by.
 *
 * <p>Package-private in spirit and public only because the repository interface names it. Nothing
 * outside this package has a use for a holding account stripped of which fund and which of the two
 * lots it stands for; that mapping stays inside {@code AsOfBalanceService}, which derived the id in
 * the first place.
 *
 * @param accountId canonical unsigned decimal u128, the form {@code participant_units} stores
 * @param unitLedgerId the fund's ledger id, which is the projection's own fund key
 */
public record HoldingAccountKey(String accountId, long unitLedgerId) {

  public HoldingAccountKey {
    Objects.requireNonNull(accountId, "accountId must not be null");
  }
}
