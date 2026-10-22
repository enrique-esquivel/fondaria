package dev.fondaria.contribution.organization;

import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link LedgerPort} that records account openings and refuses everything else.
 *
 * <p>Refusing rather than silently succeeding is the point: an update that accidentally posted a
 * transfer, or a registration that moved money, would fail here loudly instead of passing a test
 * that only checked account counts.
 *
 * <p>{@code createAccount} is idempotent on the account id, matching what TigerBeetle does with a
 * derived id — so "registering twice yields one account" is a real property of this double, not an
 * artefact of the test counting calls.
 */
final class RecordingLedgerPort implements LedgerPort {

  private final Set<AccountId> accounts = new LinkedHashSet<>();
  private final List<CreateAccountCommand> calls = new ArrayList<>();

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    calls.add(cmd);
    accounts.add(cmd.id());
    return cmd.id();
  }

  /** Distinct accounts that exist, however many times creation was attempted. */
  Set<AccountId> accounts() {
    return Set.copyOf(accounts);
  }

  /** Every attempt, including the idempotent repeats. */
  List<CreateAccountCommand> calls() {
    return List.copyOf(calls);
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    throw unsupported("lookupAccount");
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    throw unsupported("lookupAccounts");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw unsupported("createTransfer");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw unsupported("createTransfers");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw unsupported("createLinkedChain");
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    throw unsupported("lookupTransfer");
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    throw unsupported("accountTransfers");
  }

  private static AssertionError unsupported(String operation) {
    return new AssertionError(
        "Organization registration and maintenance must never call " + operation);
  }
}
