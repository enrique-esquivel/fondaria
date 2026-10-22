package dev.fondaria.balanceapi.movement;

import dev.fondaria.ledger.LedgerCodes;
import java.util.Objects;

/**
 * The subset of {@link LedgerCodes.TransferCode} that can post to a participant's own unit-ledger
 * holding account — {@code MovementTransferCode} in {@code contracts/openapi/balance-api.yaml}.
 *
 * <p>{@code CASH_RECEIPT}, {@code FEE_CHARGE}, {@code TAX_WITHHOLDING} and {@code OPENING_BALANCE}
 * are cash-ledger movements; a participant holds no cash account of their own, so they can never
 * appear on this endpoint. The subset is stated here as a type rather than left implicit so that a
 * code outside it is a loud failure rather than a movement labelled with whatever name happened to
 * match.
 *
 * <p><b>An unknown code is not skipped.</b> Dropping a row a participant's ledger really contains
 * would make the history silently incomplete, which is worse than an error (AGENTS.md §4 — never
 * skip what you cannot process).
 */
public enum MovementTransferCode {
  CONTRIBUTION_ALLOTMENT(LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT),
  VESTING(LedgerCodes.TransferCode.VESTING),
  REDEMPTION(LedgerCodes.TransferCode.REDEMPTION),
  FUND_SWITCH(LedgerCodes.TransferCode.FUND_SWITCH),
  CORRECTION(LedgerCodes.TransferCode.CORRECTION),
  NAV_ERROR_COMPENSATION(LedgerCodes.TransferCode.NAV_ERROR_COMPENSATION);

  private final LedgerCodes.TransferCode transferCode;

  MovementTransferCode(LedgerCodes.TransferCode transferCode) {
    this.transferCode = transferCode;
  }

  /** The numeric code as stored in {@code projection.transfer.code}. */
  public int ledgerCode() {
    return transferCode.code();
  }

  /**
   * @throws UnknownMovementTransferCodeException when {@code ledgerCode} is not one a participant
   *     holding account can carry — a data-integrity fault, reported as a {@code 500}
   */
  public static MovementTransferCode ofLedgerCode(int ledgerCode) {
    for (MovementTransferCode candidate : values()) {
      if (candidate.ledgerCode() == ledgerCode) {
        return candidate;
      }
    }
    throw new UnknownMovementTransferCodeException(ledgerCode);
  }

  /**
   * Empty when {@code name} is not one of these — a caller's bad query parameter, a {@code 400}.
   */
  public static java.util.Optional<MovementTransferCode> parse(String name) {
    Objects.requireNonNull(name, "name must not be null");
    for (MovementTransferCode candidate : values()) {
      if (candidate.name().equals(name)) {
        return java.util.Optional.of(candidate);
      }
    }
    return java.util.Optional.empty();
  }
}
