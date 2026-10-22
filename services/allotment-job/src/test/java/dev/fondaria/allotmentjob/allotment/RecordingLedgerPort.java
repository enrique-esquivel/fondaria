package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link LedgerPort} holding whatever a test says exists, and recording every {@code
 * createAccount}/{@code createLinkedChain} call {@link AllotmentService} makes against it — the
 * same idiom contribution-api's {@code RecordingLedgerPort} and {@code StubLedgerPort} use, kept
 * local rather than shared because this is a separate deployable (AGENTS.md §4: no service depends
 * on another service's source tree).
 *
 * <p>A successful {@link #createLinkedChain} call durably records every transfer it created, so a
 * later {@link #lookupTransfer} sees it — matching real TigerBeetle closely enough for {@link
 * AllotmentService} to exercise its own "check before resending" idempotency path (see that class's
 * javadoc on why it never simply resends and inspects the results for {@code AlreadyExists}).
 *
 * <p>Not a second {@code LedgerPort} implementation in ADR-0008's sense — that ban is about
 * production code; the one implementation is still {@code TigerBeetleLedgerAdapter}.
 */
final class RecordingLedgerPort implements LedgerPort {

  private final List<CreateAccountCommand> createdAccounts = new ArrayList<>();
  private final Set<AccountId> createdIds = new HashSet<>();
  private int accountRequests;
  private final List<List<CreateTransferCommand>> linkedChains = new ArrayList<>();
  private final Map<TransferId, LedgerTransfer> posted = new HashMap<>();
  private LedgerRejectionReason rejectEveryTransferWith;

  List<CreateAccountCommand> createdAccounts() {
    return List.copyOf(createdAccounts);
  }

  /**
   * How many ledger requests opened accounts: one per {@link #createAccount}, one per {@link
   * #createAccounts} call however many accounts it carries — the count row 26p is about.
   */
  int accountRequests() {
    return accountRequests;
  }

  List<List<CreateTransferCommand>> linkedChains() {
    return List.copyOf(linkedChains);
  }

  /** The next {@link #createLinkedChain} call rejects every transfer with this reason. */
  void rejectEveryTransferWith(LedgerRejectionReason reason) {
    this.rejectEveryTransferWith = reason;
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    accountRequests++;
    createdAccounts.add(cmd);
    createdIds.add(cmd.id());
    return cmd.id();
  }

  @Override
  public List<AccountId> createAccounts(List<CreateAccountCommand> cmds) {
    accountRequests++;
    createdAccounts.addAll(cmds);
    cmds.forEach(cmd -> createdIds.add(cmd.id()));
    return cmds.stream().map(CreateAccountCommand::id).toList();
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    // As the real ledger would, refuse a unit leg whose accounts were never opened: every
    // account a unit leg touches is AllotmentService's to open, before the chain (row 26p).
    // Cash legs are exempt -- EMPLOYER_COLLECTIONS is opened at organization registration,
    // outside this fake.
    for (CreateTransferCommand cmd : cmds) {
      if (cmd instanceof CreateTransferCommand.Unit
          && (!isCreated(cmd.debitAccountId()) || !isCreated(cmd.creditAccountId()))) {
        throw new IllegalStateException("unit leg " + cmd.id() + " posted before its accounts");
      }
    }
    linkedChains.add(List.copyOf(cmds));
    if (rejectEveryTransferWith != null) {
      return cmds.stream()
          .map(
              cmd ->
                  (TransferResult) new TransferResult.Rejected(cmd.id(), rejectEveryTransferWith))
          .toList();
    }
    List<TransferResult> results = new ArrayList<>();
    for (CreateTransferCommand cmd : cmds) {
      posted.put(cmd.id(), toLedgerTransfer(cmd));
      results.add(new TransferResult.Created(cmd.id(), Instant.EPOCH));
    }
    return results;
  }

  private boolean isCreated(AccountId id) {
    return createdIds.contains(id);
  }

  private static LedgerTransfer toLedgerTransfer(CreateTransferCommand cmd) {
    return switch (cmd) {
      case CreateTransferCommand.Cash cash ->
          new LedgerTransfer.Cash(
              cash.id(),
              cash.debitAccountId(),
              cash.creditAccountId(),
              cash.transferCode(),
              cash.amount(),
              Instant.EPOCH,
              null,
              cash.externalReference());
      case CreateTransferCommand.Unit unit ->
          new LedgerTransfer.Unit(
              unit.id(),
              unit.debitAccountId(),
              unit.creditAccountId(),
              unit.transferCode(),
              unit.fundLedgerId(),
              unit.amount(),
              Instant.EPOCH,
              null);
    };
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    return Optional.ofNullable(posted.get(id));
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    throw new UnsupportedOperationException("AllotmentService never reads a balance");
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    throw new UnsupportedOperationException("AllotmentService never reads a balance");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new UnsupportedOperationException("AllotmentService only ever fires a linked chain");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("AllotmentService only ever fires a linked chain");
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    throw new UnsupportedOperationException("not used");
  }
}
