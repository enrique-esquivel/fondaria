package dev.fondaria.ledger;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A posted transfer as read back through {@link LedgerPort}. Sealed on quantity kind for the same
 * reason as {@link LedgerAccount}: a unit movement's amount can never be read as {@link Money}, nor
 * a cash movement's amount as {@link Units}.
 */
public sealed interface LedgerTransfer permits LedgerTransfer.Cash, LedgerTransfer.Unit {

  TransferId id();

  AccountId debitAccountId();

  AccountId creditAccountId();

  LedgerCodes.TransferCode transferCode();

  Instant timestamp();

  /** Present on a {@code POST_PENDING}/{@code VOID_PENDING} transfer: the original pending id. */
  Optional<TransferId> pendingId();

  /**
   * A cash-ledger movement ({@code ledger = 484}). Always MXN, enforced below — see {@link
   * LedgerAccount.Cash}.
   */
  record Cash(
      TransferId id,
      AccountId debitAccountId,
      AccountId creditAccountId,
      LedgerCodes.TransferCode transferCode,
      Money amount,
      Instant timestamp,
      TransferId pendingIdOrNull,
      ExternalReference externalReference)
      implements LedgerTransfer {

    public Cash {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(debitAccountId, "debitAccountId must not be null");
      Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
      Objects.requireNonNull(transferCode, "transferCode must not be null");
      Objects.requireNonNull(amount, "amount must not be null");
      Objects.requireNonNull(timestamp, "timestamp must not be null");
      LedgerCodes.requireCashLedgerCurrency(amount.currency());
      externalReference = externalReference == null ? ExternalReference.NONE : externalReference;
    }

    @Override
    public Optional<TransferId> pendingId() {
      return Optional.ofNullable(pendingIdOrNull);
    }
  }

  /** A unit-ledger movement (one ledger id per fund). */
  record Unit(
      TransferId id,
      AccountId debitAccountId,
      AccountId creditAccountId,
      LedgerCodes.TransferCode transferCode,
      long fundLedgerId,
      Units amount,
      Instant timestamp,
      TransferId pendingIdOrNull)
      implements LedgerTransfer {

    public Unit {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(debitAccountId, "debitAccountId must not be null");
      Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
      Objects.requireNonNull(transferCode, "transferCode must not be null");
      Objects.requireNonNull(amount, "amount must not be null");
      Objects.requireNonNull(timestamp, "timestamp must not be null");
    }

    @Override
    public Optional<TransferId> pendingId() {
      return Optional.ofNullable(pendingIdOrNull);
    }
  }
}
