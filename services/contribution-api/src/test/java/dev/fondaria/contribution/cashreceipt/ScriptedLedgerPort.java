package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link LedgerPort} that behaves the way TigerBeetle does on the one thing this service depends
 * on: <b>a transfer id already used comes back {@link TransferResult.AlreadyExists}, and nothing
 * moves a second time.</b> That is the property the whole endpoint rests on, so a double that
 * simply accepted every transfer would let a broken implementation pass.
 *
 * <p>It records every write, so a test can assert "exactly one movement" over the calls the test
 * itself made rather than over a shared ledger's totals.
 */
final class ScriptedLedgerPort implements LedgerPort {

  private final Map<TransferId, LedgerTransfer> posted = new LinkedHashMap<>();
  private final List<CreateTransferCommand> attempts = new ArrayList<>();
  private Supplier<RuntimeException> failure;
  private LedgerRejectionReason rejection;
  private Consumer<CreateTransferCommand> observer = cmd -> {};

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    attempts.add(cmd);
    observer.accept(cmd);
    if (failure != null) {
      throw failure.get();
    }
    if (rejection != null) {
      return new TransferResult.Rejected(cmd.id(), rejection);
    }
    if (posted.containsKey(cmd.id())) {
      return new TransferResult.AlreadyExists(cmd.id());
    }
    CreateTransferCommand.Cash cash = (CreateTransferCommand.Cash) cmd;
    posted.put(
        cmd.id(),
        new LedgerTransfer.Cash(
            cmd.id(),
            cmd.debitAccountId(),
            cmd.creditAccountId(),
            cmd.transferCode(),
            cash.amount(),
            Instant.EPOCH,
            null,
            cash.externalReference()));
    return new TransferResult.Created(cmd.id(), Instant.EPOCH);
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    return Optional.ofNullable(posted.get(id));
  }

  /** Reconstructs "the ledger already holds this movement", as a crash after the post leaves it. */
  void givenPosted(CreateTransferCommand.Cash cmd) {
    createTransfer(cmd);
    attempts.clear();
  }

  /** Puts a differing movement under an id, so a lookup can disagree with the request. */
  void givenPostedAmount(TransferId id, AccountId debit, AccountId credit, long cents) {
    posted.put(
        id,
        new LedgerTransfer.Cash(
            id,
            debit,
            credit,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(cents, CurrencyCode.MXN),
            Instant.EPOCH,
            null,
            ExternalReference.NONE));
  }

  /**
   * Runs at the moment the ledger is asked to move money, so a test can inspect what the rest of
   * the system looked like <i>then</i>. This is how ADR-0011's write order is proved: the order is
   * invisible in the end state, and only observable from inside the ledger call.
   */
  void observe(Consumer<CreateTransferCommand> observer) {
    this.observer = observer;
  }

  void rejectWith(LedgerRejectionReason reason) {
    this.rejection = reason;
  }

  void failWith(Supplier<RuntimeException> failure) {
    this.failure = failure;
  }

  List<CreateTransferCommand> attempts() {
    return List.copyOf(attempts);
  }

  int movements() {
    return posted.size();
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    return cmd.id();
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return Optional.empty();
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    return List.of();
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    return cmds.stream().map(this::createTransfer).toList();
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("A cash receipt is a single transfer, never a chain");
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    throw new UnsupportedOperationException("Not used by the cash-receipt path");
  }
}
