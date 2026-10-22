package dev.fondaria.ledger;

import dev.fondaria.common.Money;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Instructs {@link LedgerPort#createTransfer} (or a batch/linked-chain call) to move one quantity
 * between two accounts on the same ledger. Sealed on quantity kind for the same reason as {@link
 * LedgerAccount}.
 *
 * <p>Build a command with {@link Cash#single}/{@link Cash#pending}/{@link Cash#postPending}/ {@link
 * Cash#voidPending} (or the {@link Unit} equivalents) rather than a canonical constructor call:
 * each factory only asks for the fields its {@link TransferKind} actually uses, so a pending
 * timeout can't be attached to a {@code SINGLE} transfer, nor a {@code POST_PENDING} built without
 * the pending id it must reference.
 *
 * <p>Business-rule rejections — insufficient balance, a cross-ledger attempt, a self-transfer, a
 * zero or negative amount — are deliberately <b>not</b> validated here: they are the ledger's call,
 * not the client's, and must come back as {@link TransferResult.Rejected} so every adapter is
 * exercised identically by the Task 2.6 conformance suite. Only the structural shape of the command
 * — required fields, and which fields a given {@link TransferKind} needs — is checked at
 * construction.
 */
public sealed interface CreateTransferCommand
    permits CreateTransferCommand.Cash, CreateTransferCommand.Unit {

  TransferId id();

  AccountId debitAccountId();

  AccountId creditAccountId();

  LedgerCodes.TransferCode transferCode();

  TransferKind kind();

  /**
   * The organization whose activity caused this movement, written to the transfer's {@code
   * user_data_64} so the CDC stream partitions tenant-first ({@code ADR-0005}, 2026-08-28
   * amendment). Defaults to {@link TenantRef#NONE}; set with {@link Cash#withTenant} / {@link
   * Unit#withTenant}. A movement between pooled fund-level accounts still carries the causing
   * tenant — the batch's organization — so a real {@code projection.transfer} row is never {@code
   * NONE}.
   */
  TenantRef tenant();

  /**
   * The ledger id this command's debit and credit account both belong to — {@link
   * LedgerCodes#CASH_LEDGER_ID} for {@link Cash}, the given fund ledger id for {@link Unit}. Used
   * by {@link ClusterAwareLedgerPort} to resolve which TigerBeetle cluster the command belongs to
   * (ADR-0005).
   */
  default long ledgerId() {
    return switch (this) {
      case Cash cash -> LedgerCodes.CASH_LEDGER_ID;
      case Unit unit -> unit.fundLedgerId();
    };
  }

  /** Only present for {@link TransferKind#PENDING}. */
  Optional<Duration> pendingTimeout();

  /** Required for {@link TransferKind#POST_PENDING} and {@link TransferKind#VOID_PENDING}. */
  Optional<TransferId> pendingId();

  private static void checkShape(
      TransferId id,
      AccountId debitAccountId,
      AccountId creditAccountId,
      LedgerCodes.TransferCode transferCode,
      TransferKind kind,
      Duration pendingTimeoutOrNull,
      TransferId pendingIdOrNull) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(debitAccountId, "debitAccountId must not be null");
    Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
    Objects.requireNonNull(transferCode, "transferCode must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    if (pendingTimeoutOrNull != null && kind != TransferKind.PENDING) {
      throw new IllegalArgumentException("pendingTimeout only applies to TransferKind.PENDING");
    }
    boolean needsPendingId = kind == TransferKind.POST_PENDING || kind == TransferKind.VOID_PENDING;
    if (needsPendingId != (pendingIdOrNull != null)) {
      throw new IllegalArgumentException(
          "pendingId is required for POST_PENDING/VOID_PENDING and forbidden otherwise");
    }
  }

  /**
   * A cash-ledger transfer ({@code ledger = 484}). Always MXN — see {@link LedgerAccount.Cash}.
   *
   * <p>Carries an optional {@link ExternalReference} tying the movement to its cause outside the
   * ledger. Only the cash variant has one so far, because only a cash movement has needed it; the
   * unit variant gains one when a task requires it rather than in advance.
   */
  record Cash(
      TransferId id,
      AccountId debitAccountId,
      AccountId creditAccountId,
      LedgerCodes.TransferCode transferCode,
      Money amount,
      TransferKind kind,
      Duration pendingTimeoutOrNull,
      TransferId pendingIdOrNull,
      ExternalReference externalReference,
      TenantRef tenant)
      implements CreateTransferCommand {

    public Cash {
      checkShape(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          kind,
          pendingTimeoutOrNull,
          pendingIdOrNull);
      Objects.requireNonNull(amount, "amount must not be null");
      LedgerCodes.requireCashLedgerCurrency(amount.currency());
      externalReference = externalReference == null ? ExternalReference.NONE : externalReference;
      tenant = tenant == null ? TenantRef.NONE : tenant;
    }

    public static Cash single(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        LedgerCodes.TransferCode transferCode,
        Money amount) {
      return single(
          id, debitAccountId, creditAccountId, transferCode, amount, ExternalReference.NONE);
    }

    /** As {@link #single}, recording what outside the ledger caused this movement. */
    public static Cash single(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        LedgerCodes.TransferCode transferCode,
        Money amount,
        ExternalReference externalReference) {
      return new Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          TransferKind.SINGLE,
          null,
          null,
          externalReference,
          TenantRef.NONE);
    }

    public static Cash pending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        LedgerCodes.TransferCode transferCode,
        Money amount,
        Duration timeout) {
      Objects.requireNonNull(timeout, "timeout must not be null");
      return new Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          TransferKind.PENDING,
          timeout,
          null,
          ExternalReference.NONE,
          TenantRef.NONE);
    }

    public static Cash postPending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        LedgerCodes.TransferCode transferCode,
        Money amount,
        TransferId pendingId) {
      Objects.requireNonNull(pendingId, "pendingId must not be null");
      return new Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          TransferKind.POST_PENDING,
          null,
          pendingId,
          ExternalReference.NONE,
          TenantRef.NONE);
    }

    public static Cash voidPending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        LedgerCodes.TransferCode transferCode,
        Money amount,
        TransferId pendingId) {
      Objects.requireNonNull(pendingId, "pendingId must not be null");
      return new Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          TransferKind.VOID_PENDING,
          null,
          pendingId,
          ExternalReference.NONE,
          TenantRef.NONE);
    }

    /** The same command, attributed to the organization whose activity caused the movement. */
    public Cash withTenant(TenantRef tenant) {
      return new Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          kind,
          pendingTimeoutOrNull,
          pendingIdOrNull,
          externalReference,
          tenant);
    }

    @Override
    public Optional<Duration> pendingTimeout() {
      return Optional.ofNullable(pendingTimeoutOrNull);
    }

    @Override
    public Optional<TransferId> pendingId() {
      return Optional.ofNullable(pendingIdOrNull);
    }
  }

  /** A unit-ledger transfer (one ledger id per fund). */
  record Unit(
      TransferId id,
      AccountId debitAccountId,
      AccountId creditAccountId,
      long fundLedgerId,
      LedgerCodes.TransferCode transferCode,
      Units amount,
      TransferKind kind,
      Duration pendingTimeoutOrNull,
      TransferId pendingIdOrNull,
      TenantRef tenant)
      implements CreateTransferCommand {

    public Unit {
      checkShape(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          kind,
          pendingTimeoutOrNull,
          pendingIdOrNull);
      Objects.requireNonNull(amount, "amount must not be null");
      tenant = tenant == null ? TenantRef.NONE : tenant;
    }

    public static Unit single(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        long fundLedgerId,
        LedgerCodes.TransferCode transferCode,
        Units amount) {
      return new Unit(
          id,
          debitAccountId,
          creditAccountId,
          fundLedgerId,
          transferCode,
          amount,
          TransferKind.SINGLE,
          null,
          null,
          TenantRef.NONE);
    }

    public static Unit pending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        long fundLedgerId,
        LedgerCodes.TransferCode transferCode,
        Units amount,
        Duration timeout) {
      Objects.requireNonNull(timeout, "timeout must not be null");
      return new Unit(
          id,
          debitAccountId,
          creditAccountId,
          fundLedgerId,
          transferCode,
          amount,
          TransferKind.PENDING,
          timeout,
          null,
          TenantRef.NONE);
    }

    public static Unit postPending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        long fundLedgerId,
        LedgerCodes.TransferCode transferCode,
        Units amount,
        TransferId pendingId) {
      Objects.requireNonNull(pendingId, "pendingId must not be null");
      return new Unit(
          id,
          debitAccountId,
          creditAccountId,
          fundLedgerId,
          transferCode,
          amount,
          TransferKind.POST_PENDING,
          null,
          pendingId,
          TenantRef.NONE);
    }

    public static Unit voidPending(
        TransferId id,
        AccountId debitAccountId,
        AccountId creditAccountId,
        long fundLedgerId,
        LedgerCodes.TransferCode transferCode,
        Units amount,
        TransferId pendingId) {
      Objects.requireNonNull(pendingId, "pendingId must not be null");
      return new Unit(
          id,
          debitAccountId,
          creditAccountId,
          fundLedgerId,
          transferCode,
          amount,
          TransferKind.VOID_PENDING,
          null,
          pendingId,
          TenantRef.NONE);
    }

    /** The same command, attributed to the organization whose activity caused the movement. */
    public Unit withTenant(TenantRef tenant) {
      return new Unit(
          id,
          debitAccountId,
          creditAccountId,
          fundLedgerId,
          transferCode,
          amount,
          kind,
          pendingTimeoutOrNull,
          pendingIdOrNull,
          tenant);
    }

    @Override
    public Optional<Duration> pendingTimeout() {
      return Optional.ofNullable(pendingTimeoutOrNull);
    }

    @Override
    public Optional<TransferId> pendingId() {
      return Optional.ofNullable(pendingIdOrNull);
    }
  }
}
