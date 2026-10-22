package dev.fondaria.balanceapi.support;

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
import dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link LedgerPort} holding whatever unit accounts a test says exist, and recording every {@code
 * lookupAccounts} batch it is asked for.
 *
 * <p>The recording is the point for one acceptance criterion in particular: "read cost does not
 * grow with participant count" is provable by asserting that a balance read issues exactly one
 * batch, of exactly {@code 2 × fundCount} ids, whatever else is in the ledger.
 *
 * <p><b>Absent accounts are simply absent from the result</b>, which is how real TigerBeetle
 * behaves and is the behaviour {@code BalanceService} reads as "never held units in this fund". A
 * double that returned a zero-balance account instead would hide a bug rather than expose one.
 *
 * <p>Not a second {@code LedgerPort} implementation in ADR-0008's sense — that ban is about
 * production code (see {@code KEY-TYPES.md}); the one implementation is still {@code
 * TigerBeetleLedgerAdapter}.
 */
public final class FakeLedgerPort implements LedgerPort {

  private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();
  private final List<List<AccountId>> lookupBatches = new ArrayList<>();
  private boolean unavailable;

  /** Opens a unit account holding {@code units}, as an allotment would have. */
  public FakeLedgerPort withUnitAccount(
      AccountId id, LedgerCodes.AccountCode code, long fundLedgerId, long units) {
    accounts.put(id, new LedgerAccount.Unit(id, code, true, fundLedgerId, Units.of(units)));
    return this;
  }

  /** Opens a cash account under an id a holding account is expected at — an invariant breach. */
  public FakeLedgerPort withCashAccountAt(AccountId id, LedgerCodes.AccountCode code) {
    accounts.put(
        id,
        new LedgerAccount.Cash(
            id, code, true, dev.fondaria.common.Money.of(0, dev.fondaria.common.CurrencyCode.MXN)));
    return this;
  }

  /** Every subsequent call fails as an unreachable cluster would. */
  public FakeLedgerPort unavailable() {
    this.unavailable = true;
    return this;
  }

  /** Every {@code lookupAccounts} batch this port was asked for, in order. */
  public List<List<AccountId>> lookupBatches() {
    return List.copyOf(lookupBatches);
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    if (unavailable) {
      throw new LedgerUnavailableException("ledger unreachable", new IllegalStateException());
    }
    lookupBatches.add(List.copyOf(ids));
    return ids.stream().map(accounts::get).filter(java.util.Objects::nonNull).toList();
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    return lookupAccounts(List.of(id)).stream().findFirst();
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    throw new UnsupportedOperationException("balance-api never creates an account");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new UnsupportedOperationException("balance-api never posts a transfer");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("balance-api never posts a transfer");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("balance-api never posts a transfer");
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
