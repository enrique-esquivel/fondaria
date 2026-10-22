package dev.fondaria.eodjob.snapshot;

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
import java.util.Objects;
import java.util.Optional;

/**
 * A ledger holding only the {@code UNITS_IN_ISSUE} balances a test put in it, and refusing every
 * write.
 *
 * <p>The refusal is the point, and it is the same one {@code FakeLedger} makes for Task 7.2: {@code
 * eod-job} wires no {@code TransferIdDeriver} and must never post. A snapshot that could move units
 * could make its own tie succeed, which is the one thing this control must not be able to do.
 * Duplicated rather than shared with the {@code reconciliation} package's double because that one
 * is package-private there — the same choice the {@code custodian} package made with {@code
 * FakeCustodianLedger}.
 */
final class FakeSnapshotLedger implements LedgerPort {

  private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();

  FakeSnapshotLedger withUnitsInIssue(AccountId id, long fundLedgerId, long unitsScaled) {
    accounts.put(
        id,
        new LedgerAccount.Unit(
            id,
            LedgerCodes.AccountCode.UNITS_IN_ISSUE,
            LedgerCodes.AccountCode.UNITS_IN_ISSUE.debitsMustNotExceedCredits(),
            fundLedgerId,
            Units.of(unitsScaled)));
    return this;
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return Optional.ofNullable(accounts.get(id));
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    return ids.stream().map(accounts::get).filter(Objects::nonNull).toList();
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    throw new AssertionError("the daily snapshot must never open a ledger account");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new AssertionError("the daily snapshot must never post to the ledger");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new AssertionError("the daily snapshot must never post to the ledger");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new AssertionError("the daily snapshot must never post to the ledger");
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
