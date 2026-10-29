package dev.fondaria.ledger.tigerbeetle;

import com.tigerbeetle.AccountBatch;
import com.tigerbeetle.AccountFilter;
import com.tigerbeetle.AccountFlags;
import com.tigerbeetle.Client;
import com.tigerbeetle.CreateAccountResultBatch;
import com.tigerbeetle.CreateAccountStatus;
import com.tigerbeetle.CreateTransferResultBatch;
import com.tigerbeetle.CreateTransferStatus;
import com.tigerbeetle.IdBatch;
import com.tigerbeetle.QueryFilter;
import com.tigerbeetle.TransferBatch;
import com.tigerbeetle.TransferFlags;
import com.tigerbeetle.UInt128;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerLimits;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LedgerPort} implemented against the official TigerBeetle Java client (Task 2.4).
 *
 * <p><b>Batching.</b> Every write — including a single transfer — goes through {@code
 * createTransfersAsync(TransferBatch)}; there is no separate single-transfer code path, since
 * batching is what keeps TigerBeetle's storage amplification down (docs/ENGINEERING-RULES.md §4).
 *
 * <p><b>Id byte order.</b> {@code AccountId}/{@code TransferId} store {@code (mostSignificantBits,
 * leastSignificantBits)}, matching {@link java.util.UUID}. TigerBeetle's own two-long setters
 * ({@code setId(long, long)}, {@code setDebitAccountId(long, long)}, …) take the <b>reverse</b>
 * order — {@code (leastSignificantBits, mostSignificantBits)} — verified empirically against
 * tigerbeetle-java {@code 0.17.4} (a batch built with {@code setId(7, 3)} reads back {@code 7} from
 * {@code UInt128.LeastSignificant} and {@code 3} from {@code UInt128.MostSignificant}; the client's
 * own {@code UInt128.asBytes(UUID)} passes {@code (uuid.getLeastSignificantBits(),
 * uuid.getMostSignificantBits())} into the same two-long writer, confirming the convention rather
 * than a one-off quirk). Getting this backwards would silently swap every id's high and low 64 bits
 * — the {@link #least(AccountId)}/{@link #most(AccountId)} pair below is the single place this is
 * applied, so it only has to be right once.
 *
 * <p><b>Amount sign.</b> TigerBeetle transfer amounts are unsigned 128-bit values with no built-in
 * zero/negative check in this version of the protocol. {@link Money#cents()} and this adapter's
 * mapping cannot be trusted to always be positive by construction, so a non-positive amount is
 * rejected locally, before it would otherwise be reinterpreted as an enormous unsigned magnitude —
 * see {@link #transferMagnitude(CreateTransferCommand)} and its callers.
 *
 * <p><b>Account balance sign.</b> For a unit-ledger account, {@link LedgerAccount.Unit#balance()}
 * is typed as {@link Units}, which cannot hold a negative value, yet the chart of accounts
 * (docs/chart-of-accounts.md §5) explicitly expects {@code UNITS_IN_ISSUE} to always equal the sum
 * of participant holdings (docs/ENGINEERING-RULES.md §2, Task 2.7 property 2 — no negation). So a
 * unit account's balance is computed in whichever direction is that account's own non-negative one:
 * {@code creditsPosted − debitsPosted} for a {@code debits_must_not_exceed_credits} account
 * (participant holdings, ledger-enforced non-negative), {@code debitsPosted − creditsPosted} for
 * the contra accounts ({@code UNITS_IN_ISSUE}, {@code UNIT_ROUNDING}). A cash account's balance is
 * always {@code creditsPosted − debitsPosted}; {@link Money} can hold a negative value, and this is
 * the convention that reproduces every figure in the chart of accounts' two worked examples
 * exactly.
 */
public final class TigerBeetleLedgerAdapter implements LedgerPort {

  private static final Logger LOGGER = LoggerFactory.getLogger(TigerBeetleLedgerAdapter.class);

  private final Client client;
  private final Duration callTimeout;
  private final ObservationRegistry observationRegistry;

  public TigerBeetleLedgerAdapter(
      Client client, Duration callTimeout, ObservationRegistry observationRegistry) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.callTimeout = Objects.requireNonNull(callTimeout, "callTimeout must not be null");
    this.observationRegistry =
        Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
    if (callTimeout.isNegative() || callTimeout.isZero()) {
      throw new IllegalArgumentException("callTimeout must be positive, got: " + callTimeout);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // LedgerPort
  // ---------------------------------------------------------------------------------------------

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    return observe("ledger.tigerbeetle.create_account", () -> doCreateAccount(cmd));
  }

  @Override
  public List<AccountId> createAccounts(List<CreateAccountCommand> cmds) {
    return observe("ledger.tigerbeetle.create_accounts", () -> doCreateAccounts(cmds));
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return observe(
        "ledger.tigerbeetle.lookup_account",
        () -> {
          List<LedgerAccount> found = doLookupAccounts(List.of(id));
          return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        });
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    return observe("ledger.tigerbeetle.lookup_accounts", () -> doLookupAccounts(ids));
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    return createTransfers(List.of(cmd)).get(0);
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    return observe("ledger.tigerbeetle.create_transfers", () -> doCreateTransfers(cmds));
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    return observe("ledger.tigerbeetle.create_linked_chain", () -> doCreateLinkedChain(cmds));
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    return observe(
        "ledger.tigerbeetle.lookup_transfer",
        () -> {
          IdBatch idBatch = new IdBatch(1);
          idBatch.add(least(id), most(id));
          TransferBatch results = await(client.lookupTransfersAsync(idBatch));
          return results.next() ? Optional.of(toLedgerTransfer(results)) : Optional.empty();
        });
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    return observe("ledger.tigerbeetle.account_transfers", () -> doAccountTransfers(id, query));
  }

  /**
   * One {@code query_transfers} with an empty filter, newest first, limit one: TigerBeetle walks
   * its timestamp index from the end, so this costs one round trip whatever the history holds.
   */
  @Override
  public Optional<Instant> latestTransferTimestamp() {
    return observe(
        "ledger.tigerbeetle.latest_transfer_timestamp",
        () -> {
          QueryFilter filter = new QueryFilter();
          filter.setLimit(1);
          filter.setReversed(true);
          TransferBatch results = await(client.queryTransfersAsync(filter));
          return results.next()
              ? Optional.of(Instant.ofEpochSecond(0, results.getTimestamp()))
              : Optional.empty();
        });
  }

  // ---------------------------------------------------------------------------------------------
  // createAccount
  // ---------------------------------------------------------------------------------------------

  private AccountId doCreateAccount(CreateAccountCommand cmd) {
    AccountBatch batch = new AccountBatch(1);
    writeAccountElement(batch, cmd);

    CreateAccountResultBatch results = await(client.createAccountsAsync(batch));
    if (!results.next()) {
      throw new IllegalStateException(
          "TigerBeetle returned no result for account creation, id=" + cmd.id());
    }
    CreateAccountStatus status = results.getStatus();
    if (status == CreateAccountStatus.Created || isAccountAlreadyExists(status)) {
      if (status != CreateAccountStatus.Created) {
        LOGGER.warn(
            "Account already exists, treating createAccount as idempotent, status={}", status);
      }
      return cmd.id();
    }
    LOGGER.error("TigerBeetle rejected account creation, status={}", status);
    throw new LedgerAccountRejectedException(cmd.id(), status.toString());
  }

  /**
   * One request per {@link LedgerLimits#MAX_ACCOUNTS_PER_REQUEST} accounts, each result read by its
   * position — the same per-account rules as {@link #doCreateAccount}. Every result is checked
   * before anything is thrown, so a refusal names the first refused account, never a later one. A
   * plain "already exists" is counted and logged once per request, not once per account: allotment
   * re-opens thousands of existing holdings on every batch, and the per-account warning rolled the
   * container log over every ~15 s (TASK-LOG.md step 99).
   */
  private List<AccountId> doCreateAccounts(List<CreateAccountCommand> cmds) {
    for (int from = 0; from < cmds.size(); from += LedgerLimits.MAX_ACCOUNTS_PER_REQUEST) {
      List<CreateAccountCommand> chunk =
          cmds.subList(from, Math.min(cmds.size(), from + LedgerLimits.MAX_ACCOUNTS_PER_REQUEST));
      AccountBatch batch = new AccountBatch(chunk.size());
      for (CreateAccountCommand cmd : chunk) {
        writeAccountElement(batch, cmd);
      }
      CreateAccountResultBatch results = await(client.createAccountsAsync(batch));
      CreateAccountStatus[] statuses = new CreateAccountStatus[chunk.size()];
      while (results.next()) {
        statuses[results.getPosition()] = results.getStatus();
      }
      int alreadyExisting = 0;
      for (int i = 0; i < chunk.size(); i++) {
        CreateAccountStatus status = statuses[i];
        if (status == null) {
          throw new IllegalStateException(
              "TigerBeetle returned no result for account creation, id=" + chunk.get(i).id());
        }
        if (status == CreateAccountStatus.Created) {
          continue;
        }
        if (status == CreateAccountStatus.Exists) {
          alreadyExisting++;
          continue;
        }
        if (isAccountAlreadyExists(status)) {
          // Accepted as createAccount accepts it, but never folded into the count: an
          // ExistsWithDifferent* means the account was opened with other flags, tenant, ledger
          // or code than this caller expects, and this line is the only place that shows.
          LOGGER.warn(
              "Account already exists with different fields, treating createAccounts as"
                  + " idempotent, id={}, status={}",
              chunk.get(i).id(),
              status);
          continue;
        }
        LOGGER.error("TigerBeetle rejected account creation, status={}", status);
        throw new LedgerAccountRejectedException(chunk.get(i).id(), status.toString());
      }
      if (alreadyExisting > 0) {
        LOGGER.info(
            "{} of {} accounts already existed, treating createAccounts as idempotent",
            alreadyExisting,
            chunk.size());
      }
    }
    return cmds.stream().map(CreateAccountCommand::id).toList();
  }

  private static void writeAccountElement(AccountBatch batch, CreateAccountCommand cmd) {
    batch.add();
    batch.setId(least(cmd.id()), most(cmd.id()));
    batch.setLedger(Math.toIntExact(cmd.ledgerId()));
    batch.setCode(cmd.accountCode().code());
    // The tenant this account belongs to (ADR-0005), or 0 for a pooled fund-level account.
    // Surfaces on ledger.entries.v1 as accountUserData64 — how the sparse unit projection
    // (Task 5.5) partitions participant_units tenant-first.
    batch.setUserData64(cmd.tenant().value());
    batch.setFlags(
        cmd.debitsMustNotExceedCredits()
            ? AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS
            : AccountFlags.NONE);
  }

  private static boolean isAccountAlreadyExists(CreateAccountStatus status) {
    return switch (status) {
      case Exists,
          ExistsWithDifferentFlags,
          ExistsWithDifferentUserData128,
          ExistsWithDifferentUserData64,
          ExistsWithDifferentUserData32,
          ExistsWithDifferentLedger,
          ExistsWithDifferentCode ->
          true;
      default -> false;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // lookupAccount / lookupAccounts
  // ---------------------------------------------------------------------------------------------

  private List<LedgerAccount> doLookupAccounts(List<AccountId> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    IdBatch idBatch = new IdBatch(ids.size());
    for (AccountId id : ids) {
      idBatch.add(least(id), most(id));
    }
    AccountBatch results = await(client.lookupAccountsAsync(idBatch));
    List<LedgerAccount> out = new ArrayList<>();
    while (results.next()) {
      out.add(toLedgerAccount(results));
    }
    return List.copyOf(out);
  }

  private LedgerAccount toLedgerAccount(AccountBatch row) {
    AccountId id =
        AccountId.of(row.getId(UInt128.MostSignificant), row.getId(UInt128.LeastSignificant));
    long ledger = row.getLedger();
    LedgerCodes.AccountCode accountCode = accountCodeFromWire(row.getCode());
    boolean debitsMustNotExceedCredits = AccountFlags.hasDebitsMustNotExceedCredits(row.getFlags());
    long debitsPosted = row.getDebitsPosted().longValueExact();
    long creditsPosted = row.getCreditsPosted().longValueExact();

    if (ledger == LedgerCodes.CASH_LEDGER_ID) {
      Money balance = Money.of(Math.subtractExact(creditsPosted, debitsPosted), CurrencyCode.MXN);
      return new LedgerAccount.Cash(id, accountCode, debitsMustNotExceedCredits, balance);
    }
    long magnitude =
        debitsMustNotExceedCredits
            ? Math.subtractExact(creditsPosted, debitsPosted)
            : Math.subtractExact(debitsPosted, creditsPosted);
    Units balance = Units.of(magnitude);
    return new LedgerAccount.Unit(id, accountCode, debitsMustNotExceedCredits, ledger, balance);
  }

  // ---------------------------------------------------------------------------------------------
  // createTransfer(s) / createLinkedChain
  // ---------------------------------------------------------------------------------------------

  private List<TransferResult> doCreateTransfers(List<CreateTransferCommand> cmds) {
    if (cmds.isEmpty()) {
      return List.of();
    }
    TransferResult[] out = new TransferResult[cmds.size()];
    List<Integer> sendIndices = new ArrayList<>();
    for (int i = 0; i < cmds.size(); i++) {
      CreateTransferCommand cmd = cmds.get(i);
      if (transferMagnitude(cmd) <= 0) {
        out[i] =
            new TransferResult.Rejected(cmd.id(), LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);
      } else {
        sendIndices.add(i);
      }
    }
    if (!sendIndices.isEmpty()) {
      TransferBatch batch = new TransferBatch(sendIndices.size());
      for (int index : sendIndices) {
        writeTransferElement(batch, cmds.get(index), false);
      }
      CreateTransferResultBatch results = await(client.createTransfersAsync(batch));
      while (results.next()) {
        int originalIndex = sendIndices.get(results.getPosition());
        out[originalIndex] = mapTransferResult(cmds.get(originalIndex), results);
      }
    }
    return List.of(out);
  }

  /**
   * A locally-invalid amount anywhere in the chain must fail the whole chain without sending
   * anything to TigerBeetle — sending a shortened batch would apply the other legs independently,
   * breaking the all-or-nothing contract {@link LedgerPort#createLinkedChain} promises.
   */
  private List<TransferResult> doCreateLinkedChain(List<CreateTransferCommand> cmds) {
    if (cmds.isEmpty()) {
      return List.of();
    }
    int invalidIndex = -1;
    for (int i = 0; i < cmds.size(); i++) {
      if (transferMagnitude(cmds.get(i)) <= 0) {
        invalidIndex = i;
        break;
      }
    }
    if (invalidIndex >= 0) {
      List<TransferResult> out = new ArrayList<>(cmds.size());
      for (int i = 0; i < cmds.size(); i++) {
        LedgerRejectionReason reason =
            i == invalidIndex
                ? LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT
                : LedgerRejectionReason.LINKED_CHAIN_FAILED;
        out.add(new TransferResult.Rejected(cmds.get(i).id(), reason));
      }
      return List.copyOf(out);
    }

    TransferBatch batch = new TransferBatch(cmds.size());
    for (int i = 0; i < cmds.size(); i++) {
      writeTransferElement(batch, cmds.get(i), i < cmds.size() - 1);
    }
    CreateTransferResultBatch results = await(client.createTransfersAsync(batch));
    TransferResult[] out = new TransferResult[cmds.size()];
    while (results.next()) {
      int position = results.getPosition();
      out[position] = mapTransferResult(cmds.get(position), results);
    }
    return List.of(out);
  }

  private void writeTransferElement(
      TransferBatch batch, CreateTransferCommand cmd, boolean linked) {
    batch.add();
    batch.setId(least(cmd.id()), most(cmd.id()));
    batch.setDebitAccountId(least(cmd.debitAccountId()), most(cmd.debitAccountId()));
    batch.setCreditAccountId(least(cmd.creditAccountId()), most(cmd.creditAccountId()));
    batch.setLedger(Math.toIntExact(ledgerIdOf(cmd)));
    batch.setCode(cmd.transferCode().code());
    batch.setAmount(transferMagnitude(cmd));
    // Zero is TigerBeetle's own "unset", which is exactly ExternalReference.NONE's encoding, so an
    // absent reference needs no special case here.
    if (cmd instanceof CreateTransferCommand.Cash cash) {
      ExternalReference reference = cash.externalReference();
      batch.setUserData128(reference.leastSignificantBits(), reference.mostSignificantBits());
    }
    // The organization whose activity caused this movement (ADR-0005, 2026-08-28 amendment).
    // Carried on ledger.transfers.v1 as userData64; the transfer projection (Task 5.4) reads it
    // to partition projection.transfer tenant-first. A movement between pooled fund-level
    // accounts still carries the causing tenant, so it is never 0 for a real contribution flow.
    batch.setUserData64(cmd.tenant().value());

    int flags = linked ? TransferFlags.LINKED : TransferFlags.NONE;
    switch (cmd.kind()) {
      case SINGLE -> {}
      case PENDING -> {
        flags |= TransferFlags.PENDING;
        Duration timeout = cmd.pendingTimeout().orElseThrow();
        batch.setTimeout(Math.toIntExact(timeout.toSeconds()));
      }
      case POST_PENDING -> {
        flags |= TransferFlags.POST_PENDING_TRANSFER;
        TransferId pendingId = cmd.pendingId().orElseThrow();
        batch.setPendingId(least(pendingId), most(pendingId));
      }
      case VOID_PENDING -> {
        flags |= TransferFlags.VOID_PENDING_TRANSFER;
        TransferId pendingId = cmd.pendingId().orElseThrow();
        batch.setPendingId(least(pendingId), most(pendingId));
      }
    }
    batch.setFlags(flags);
  }

  private TransferResult mapTransferResult(
      CreateTransferCommand cmd, CreateTransferResultBatch row) {
    CreateTransferStatus status = row.getStatus();
    TransferId id = cmd.id();
    if (status == CreateTransferStatus.Created) {
      return new TransferResult.Created(id, Instant.ofEpochSecond(0, row.getTimestamp()));
    }
    if (isTransferAlreadyExists(status)) {
      return new TransferResult.AlreadyExists(id);
    }
    LedgerRejectionReason reason =
        switch (status) {
          case DebitAccountNotFound, CreditAccountNotFound ->
              LedgerRejectionReason.ACCOUNT_NOT_FOUND;
          case AccountsMustHaveTheSameLedger, TransferMustHaveTheSameLedgerAsAccounts ->
              LedgerRejectionReason.CROSS_LEDGER;
          case AccountsMustBeDifferent -> LedgerRejectionReason.SELF_TRANSFER;
          case ExceedsCredits, ExceedsDebits ->
              isUnit(cmd)
                  ? LedgerRejectionReason.INSUFFICIENT_UNITS
                  : LedgerRejectionReason.INSUFFICIENT_FUNDS;
          case PendingTransferNotFound -> LedgerRejectionReason.PENDING_TRANSFER_NOT_FOUND;
          case PendingTransferAlreadyPosted ->
              LedgerRejectionReason.PENDING_TRANSFER_ALREADY_POSTED;
          case PendingTransferAlreadyVoided ->
              LedgerRejectionReason.PENDING_TRANSFER_ALREADY_VOIDED;
          case PendingTransferExpired -> LedgerRejectionReason.PENDING_TRANSFER_EXPIRED;
          case LinkedEventFailed, LinkedEventChainOpen, IdAlreadyFailed ->
              LedgerRejectionReason.LINKED_CHAIN_FAILED;
          default -> {
            LOGGER.error("Unmapped TigerBeetle transfer rejection, status={}", status);
            yield LedgerRejectionReason.UNKNOWN;
          }
        };
    return new TransferResult.Rejected(id, reason);
  }

  private static boolean isTransferAlreadyExists(CreateTransferStatus status) {
    return switch (status) {
      case Exists,
          ExistsWithDifferentFlags,
          ExistsWithDifferentPendingId,
          ExistsWithDifferentTimeout,
          ExistsWithDifferentDebitAccountId,
          ExistsWithDifferentCreditAccountId,
          ExistsWithDifferentAmount,
          ExistsWithDifferentUserData128,
          ExistsWithDifferentUserData64,
          ExistsWithDifferentUserData32,
          ExistsWithDifferentLedger,
          ExistsWithDifferentCode ->
          true;
      default -> false;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // lookupTransfer / accountTransfers
  // ---------------------------------------------------------------------------------------------

  private List<LedgerTransfer> doAccountTransfers(AccountId id, TransferQuery query) {
    AccountFilter filter = new AccountFilter();
    filter.setAccountId(least(id), most(id));
    filter.setLimit(query.limit());
    filter.setDebits(true);
    filter.setCredits(true);
    boolean reversed = query.direction() == TransferQuery.SortDirection.DESCENDING;
    filter.setReversed(reversed);
    if (query.after().isPresent()) {
      TransferId cursor = query.after().get();
      long cursorTimestamp =
          rawTimestampOf(cursor)
              .orElseThrow(
                  () -> new IllegalArgumentException("Cursor transfer not found: " + cursor));
      if (reversed) {
        filter.setTimestampMax(cursorTimestamp - 1);
      } else {
        filter.setTimestampMin(cursorTimestamp + 1);
      }
    }
    TransferBatch results = await(client.getAccountTransfersAsync(filter));
    List<LedgerTransfer> out = new ArrayList<>();
    while (results.next()) {
      out.add(toLedgerTransfer(results));
    }
    return List.copyOf(out);
  }

  private OptionalLong rawTimestampOf(TransferId id) {
    IdBatch idBatch = new IdBatch(1);
    idBatch.add(least(id), most(id));
    TransferBatch results = await(client.lookupTransfersAsync(idBatch));
    return results.next() ? OptionalLong.of(results.getTimestamp()) : OptionalLong.empty();
  }

  private LedgerTransfer toLedgerTransfer(TransferBatch row) {
    TransferId id =
        TransferId.of(row.getId(UInt128.MostSignificant), row.getId(UInt128.LeastSignificant));
    AccountId debitAccountId =
        AccountId.of(
            row.getDebitAccountId(UInt128.MostSignificant),
            row.getDebitAccountId(UInt128.LeastSignificant));
    AccountId creditAccountId =
        AccountId.of(
            row.getCreditAccountId(UInt128.MostSignificant),
            row.getCreditAccountId(UInt128.LeastSignificant));
    LedgerCodes.TransferCode transferCode = transferCodeFromWire(row.getCode());
    Instant timestamp = Instant.ofEpochSecond(0, row.getTimestamp());
    long pendingLeast = row.getPendingId(UInt128.LeastSignificant);
    long pendingMost = row.getPendingId(UInt128.MostSignificant);
    TransferId pendingIdOrNull =
        (pendingLeast == 0 && pendingMost == 0) ? null : TransferId.of(pendingMost, pendingLeast);
    long ledger = row.getLedger();
    long magnitude = row.getAmount().longValueExact();

    if (ledger == LedgerCodes.CASH_LEDGER_ID) {
      Money amount = Money.of(magnitude, CurrencyCode.MXN);
      ExternalReference externalReference =
          ExternalReference.of(
              row.getUserData128(UInt128.MostSignificant),
              row.getUserData128(UInt128.LeastSignificant));
      return new LedgerTransfer.Cash(
          id,
          debitAccountId,
          creditAccountId,
          transferCode,
          amount,
          timestamp,
          pendingIdOrNull,
          externalReference);
    }
    Units amount = Units.of(magnitude);
    return new LedgerTransfer.Unit(
        id,
        debitAccountId,
        creditAccountId,
        transferCode,
        ledger,
        amount,
        timestamp,
        pendingIdOrNull);
  }

  // ---------------------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------------------

  private static long transferMagnitude(CreateTransferCommand cmd) {
    return switch (cmd) {
      case CreateTransferCommand.Cash cash -> cash.amount().cents();
      case CreateTransferCommand.Unit unit -> unit.amount().scaled();
    };
  }

  private static long ledgerIdOf(CreateTransferCommand cmd) {
    return switch (cmd) {
      case CreateTransferCommand.Cash cash -> LedgerCodes.CASH_LEDGER_ID;
      case CreateTransferCommand.Unit unit -> unit.fundLedgerId();
    };
  }

  private static boolean isUnit(CreateTransferCommand cmd) {
    return cmd instanceof CreateTransferCommand.Unit;
  }

  private static LedgerCodes.AccountCode accountCodeFromWire(int code) {
    for (LedgerCodes.AccountCode candidate : LedgerCodes.AccountCode.values()) {
      if (candidate.code() == code) {
        return candidate;
      }
    }
    throw new IllegalStateException("Unknown TigerBeetle account code: " + code);
  }

  private static LedgerCodes.TransferCode transferCodeFromWire(int code) {
    for (LedgerCodes.TransferCode candidate : LedgerCodes.TransferCode.values()) {
      if (candidate.code() == code) {
        return candidate;
      }
    }
    throw new IllegalStateException("Unknown TigerBeetle transfer code: " + code);
  }

  /** See the id byte order note in this class's Javadoc. */
  private static long least(AccountId id) {
    return id.leastSignificantBits();
  }

  private static long most(AccountId id) {
    return id.mostSignificantBits();
  }

  private static long least(TransferId id) {
    return id.leastSignificantBits();
  }

  private static long most(TransferId id) {
    return id.mostSignificantBits();
  }

  private <T> T observe(String name, Supplier<T> call) {
    return Observation.createNotStarted(name, observationRegistry).observe(call);
  }

  private <T> T await(CompletableFuture<T> future) {
    try {
      return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LedgerUnavailableException("Interrupted waiting for TigerBeetle", e);
    } catch (ExecutionException e) {
      throw new LedgerUnavailableException("TigerBeetle call failed", e.getCause());
    } catch (TimeoutException e) {
      throw new LedgerUnavailableException(
          "TigerBeetle call did not complete within " + callTimeout, e);
    }
  }
}
