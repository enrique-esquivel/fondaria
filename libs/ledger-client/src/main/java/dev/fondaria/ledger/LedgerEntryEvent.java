package dev.fondaria.ledger;

import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.util.Objects;
import java.util.Optional;

/**
 * One decoded message from the Redpanda topic {@code ledger.entries.v1} — one leg (debit or credit)
 * of a transfer, for one TigerBeetle CDC change event, carrying that one account's post-event
 * balance counters.
 *
 * <p>Sealed on quantity kind, like {@link LedgerTransferEvent}: on a {@link Cash} entry every
 * quantity — the transfer amount and all four balance counters — is {@link Money} in MXN cents; on
 * a {@link Unit} entry every quantity is {@link Units} at scale 1e-8. {@link LedgerEventDecoder}
 * chooses the variant by {@code ledger} id. A quantity that does not fit a signed 64-bit {@code
 * long} is a decode error, never a truncation.
 */
public sealed interface LedgerEntryEvent permits LedgerEntryEvent.Cash, LedgerEntryEvent.Unit {

  /** Always {@code "v1"} for a message decoded through this type. */
  String schemaVersion();

  ChangeEventType eventType();

  /** The TigerBeetle ledger id: {@code 484} for {@link Cash}, {@code >= 1001} for {@link Unit}. */
  long ledger();

  /** The change event's ledger commit timestamp, u64 nanoseconds since the Unix epoch. */
  String timestamp();

  String transferId();

  int transferCode();

  /** The transfer's own commit timestamp, u64 nanoseconds since the Unix epoch. */
  String transferTimestamp();

  /** Which leg of the transfer this entry is. */
  EntryDirection direction();

  /** This entry's account id (u128) — the partition key of {@code ledger.entries.v1}. */
  String accountId();

  int accountCode();

  int accountFlags();

  String accountUserData128();

  String accountUserData64();

  long accountUserData32();

  /** The originating pending transfer id on a two-phase post/void; empty otherwise. */
  Optional<String> pendingId();

  /** A cash-ledger entry ({@code ledger = 484}). Every quantity is MXN cents. */
  record Cash(
      String schemaVersion,
      ChangeEventType eventType,
      long ledger,
      String timestamp,
      String transferId,
      int transferCode,
      String transferTimestamp,
      EntryDirection direction,
      String accountId,
      int accountCode,
      int accountFlags,
      String accountUserData128,
      String accountUserData64,
      long accountUserData32,
      Optional<String> pendingId,
      Money amount,
      Money debitsPending,
      Money debitsPosted,
      Money creditsPending,
      Money creditsPosted)
      implements LedgerEntryEvent {

    public Cash {
      requireCommon(
          schemaVersion,
          eventType,
          timestamp,
          transferId,
          transferTimestamp,
          direction,
          accountId,
          accountUserData128,
          accountUserData64,
          pendingId);
      requireCash(amount, debitsPending, debitsPosted, creditsPending, creditsPosted);
    }
  }

  /** A unit-ledger entry (one ledger id per fund). Every quantity is units at scale 1e-8. */
  record Unit(
      String schemaVersion,
      ChangeEventType eventType,
      long ledger,
      String timestamp,
      String transferId,
      int transferCode,
      String transferTimestamp,
      EntryDirection direction,
      String accountId,
      int accountCode,
      int accountFlags,
      String accountUserData128,
      String accountUserData64,
      long accountUserData32,
      Optional<String> pendingId,
      long fundLedgerId,
      Units amount,
      Units debitsPending,
      Units debitsPosted,
      Units creditsPending,
      Units creditsPosted)
      implements LedgerEntryEvent {

    public Unit {
      requireCommon(
          schemaVersion,
          eventType,
          timestamp,
          transferId,
          transferTimestamp,
          direction,
          accountId,
          accountUserData128,
          accountUserData64,
          pendingId);
      Objects.requireNonNull(amount, "amount must not be null");
      Objects.requireNonNull(debitsPending, "debitsPending must not be null");
      Objects.requireNonNull(debitsPosted, "debitsPosted must not be null");
      Objects.requireNonNull(creditsPending, "creditsPending must not be null");
      Objects.requireNonNull(creditsPosted, "creditsPosted must not be null");
    }
  }

  private static void requireCommon(
      String schemaVersion,
      ChangeEventType eventType,
      String timestamp,
      String transferId,
      String transferTimestamp,
      EntryDirection direction,
      String accountId,
      String accountUserData128,
      String accountUserData64,
      Optional<String> pendingId) {
    Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(transferId, "transferId must not be null");
    Objects.requireNonNull(transferTimestamp, "transferTimestamp must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(accountUserData128, "accountUserData128 must not be null");
    Objects.requireNonNull(accountUserData64, "accountUserData64 must not be null");
    Objects.requireNonNull(pendingId, "pendingId must not be null");
  }

  private static void requireCash(
      Money amount,
      Money debitsPending,
      Money debitsPosted,
      Money creditsPending,
      Money creditsPosted) {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(debitsPending, "debitsPending must not be null");
    Objects.requireNonNull(debitsPosted, "debitsPosted must not be null");
    Objects.requireNonNull(creditsPending, "creditsPending must not be null");
    Objects.requireNonNull(creditsPosted, "creditsPosted must not be null");
    LedgerCodes.requireCashLedgerCurrency(amount.currency());
    LedgerCodes.requireCashLedgerCurrency(debitsPending.currency());
    LedgerCodes.requireCashLedgerCurrency(debitsPosted.currency());
    LedgerCodes.requireCashLedgerCurrency(creditsPending.currency());
    LedgerCodes.requireCashLedgerCurrency(creditsPosted.currency());
  }
}
