package dev.fondaria.ledger;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.util.Objects;
import java.util.Optional;

/**
 * One decoded message from the Redpanda topic {@code ledger.transfers.v1} — the transfer that
 * moved, for one TigerBeetle CDC change event.
 *
 * <p>Sealed on quantity kind for the same reason as {@link dev.fondaria.ledger.LedgerTransfer}: a
 * unit movement's amount can never be read as {@link Money}, nor a cash movement's amount as {@link
 * Units}. {@link LedgerEventDecoder} chooses the variant by {@code ledger} id — {@code 484} is the
 * MXN cash ledger, {@code >= 1001} a per-fund unit ledger.
 *
 * <p>The large 128-bit fields ({@link #transferId()}, {@link #userData128()}, the account ids and
 * both timestamps) stay canonical base-10 strings: the bridge is a transport, consumers key and
 * correlate on these values, and forcing a u128 into a {@code long} would overflow. Only the
 * transfer <i>amount</i> becomes a domain quantity, because that is the number a projection does
 * arithmetic on. An amount that does not fit a signed 64-bit {@code long} is a decode error, never
 * a truncation.
 */
public sealed interface LedgerTransferEvent
    permits LedgerTransferEvent.Cash, LedgerTransferEvent.Unit {

  /** Always {@code "v1"} for a message decoded through this type. */
  String schemaVersion();

  ChangeEventType eventType();

  /** The TigerBeetle ledger id: {@code 484} for {@link Cash}, {@code >= 1001} for {@link Unit}. */
  long ledger();

  /** The change event's ledger commit timestamp, u64 nanoseconds since the Unix epoch. */
  String timestamp();

  /** The transfer id (u128) — the partition key of {@code ledger.transfers.v1}. */
  String transferId();

  /** The originating pending transfer id on a two-phase post/void; empty otherwise. */
  Optional<String> pendingId();

  int transferCode();

  int transferFlags();

  /** The transfer's own commit timestamp, u64 nanoseconds since the Unix epoch. */
  String transferTimestamp();

  String userData128();

  String userData64();

  long userData32();

  String debitAccountId();

  String creditAccountId();

  int debitAccountCode();

  int creditAccountCode();

  /** A cash-ledger transfer ({@code ledger = 484}). {@link #amount()} is MXN cents. */
  record Cash(
      String schemaVersion,
      ChangeEventType eventType,
      long ledger,
      String timestamp,
      String transferId,
      Optional<String> pendingId,
      int transferCode,
      int transferFlags,
      String transferTimestamp,
      String userData128,
      String userData64,
      long userData32,
      String debitAccountId,
      String creditAccountId,
      int debitAccountCode,
      int creditAccountCode,
      Money amount)
      implements LedgerTransferEvent {

    public Cash {
      requireCommon(
          schemaVersion,
          eventType,
          timestamp,
          transferId,
          pendingId,
          transferTimestamp,
          userData128,
          userData64,
          debitAccountId,
          creditAccountId);
      Objects.requireNonNull(amount, "amount must not be null");
      LedgerCodes.requireCashLedgerCurrency(amount.currency());
    }
  }

  /** A unit-ledger transfer (one ledger id per fund). {@link #amount()} is units at scale 1e-8. */
  record Unit(
      String schemaVersion,
      ChangeEventType eventType,
      long ledger,
      String timestamp,
      String transferId,
      Optional<String> pendingId,
      int transferCode,
      int transferFlags,
      String transferTimestamp,
      String userData128,
      String userData64,
      long userData32,
      String debitAccountId,
      String creditAccountId,
      int debitAccountCode,
      int creditAccountCode,
      long fundLedgerId,
      Units amount)
      implements LedgerTransferEvent {

    public Unit {
      requireCommon(
          schemaVersion,
          eventType,
          timestamp,
          transferId,
          pendingId,
          transferTimestamp,
          userData128,
          userData64,
          debitAccountId,
          creditAccountId);
      Objects.requireNonNull(amount, "amount must not be null");
    }
  }

  private static void requireCommon(
      String schemaVersion,
      ChangeEventType eventType,
      String timestamp,
      String transferId,
      Optional<String> pendingId,
      String transferTimestamp,
      String userData128,
      String userData64,
      String debitAccountId,
      String creditAccountId) {
    Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(pendingId, "pendingId must not be null");
    Objects.requireNonNull(transferTimestamp, "transferTimestamp must not be null");
    Objects.requireNonNull(userData128, "userData128 must not be null");
    Objects.requireNonNull(userData64, "userData64 must not be null");
    Objects.requireNonNull(debitAccountId, "debitAccountId must not be null");
    Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
  }
}
