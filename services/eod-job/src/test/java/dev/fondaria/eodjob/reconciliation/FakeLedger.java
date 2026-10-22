package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ledger that holds only what a test put in it, and refuses every write.
 *
 * <p>The refusal is the point. {@code eod-job} wires no {@code TransferIdDeriver} and must never
 * post — a reconciliation that could write to the ledger could make itself balance. If a future
 * change starts writing here, these tests fail with the reason rather than quietly passing.
 *
 * <p>Also records every batch of ids it was asked for, so a test can assert that the pass reads in
 * bounded batches rather than one request per account.
 */
final class FakeLedger implements LedgerPort {

  private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();
  private final List<Integer> lookupBatchSizes = new ArrayList<>();

  void putUnits(AccountId id, LedgerCodes.AccountCode code, long fundLedgerId, long unitsScaled) {
    accounts.put(
        id,
        new LedgerAccount.Unit(
            id, code, code.debitsMustNotExceedCredits(), fundLedgerId, Units.of(unitsScaled)));
  }

  void putCash(AccountId id, LedgerCodes.AccountCode code, long cents) {
    accounts.put(
        id,
        new LedgerAccount.Cash(
            id, code, code.debitsMustNotExceedCredits(), Money.of(cents, CurrencyCode.MXN)));
  }

  List<Integer> lookupBatchSizes() {
    return List.copyOf(lookupBatchSizes);
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return Optional.ofNullable(accounts.get(id));
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    lookupBatchSizes.add(ids.size());
    return ids.stream().map(accounts::get).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    throw new AssertionError("the daily close must never open a ledger account");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new AssertionError("the daily close must never post to the ledger");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new AssertionError("the daily close must never post to the ledger");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new AssertionError("the daily close must never post to the ledger");
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    return Optional.empty();
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    return List.of();
  }
}
