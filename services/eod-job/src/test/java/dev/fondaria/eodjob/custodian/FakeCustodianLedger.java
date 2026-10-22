package dev.fondaria.eodjob.custodian;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A ledger that holds only what a test put in it, and refuses every write — the {@code custodian}
 * package's counterpart of {@code reconciliation.FakeLedger} (Task 7.2). The refusal proves AC4:
 * "no code path adjusts participant units to match the custodian".
 */
final class FakeCustodianLedger implements LedgerPort {

  private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();

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

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return Optional.ofNullable(accounts.get(id));
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    return ids.stream().map(accounts::get).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    throw new AssertionError("custodian reconciliation must never open a ledger account");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new AssertionError("custodian reconciliation must never post to the ledger");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new AssertionError("custodian reconciliation must never post to the ledger");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new AssertionError("custodian reconciliation must never post to the ledger");
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
