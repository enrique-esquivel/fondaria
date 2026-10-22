package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A minimal {@link LedgerPort} fake tracking unit-account balances and posted transfers — enough
 * for {@link VestingServiceTest} to exercise {@link VestingService}'s own ledger reads/writes
 * without a real TigerBeetle. Not a second {@code LedgerPort} implementation in ADR-0008's sense —
 * that ban is about production code.
 */
final class FakeVestingLedgerPort implements LedgerPort {

  private final Set<AccountId> openedAccounts = new HashSet<>();
  private final Map<AccountId, Units> unitBalances = new HashMap<>();
  private final Map<TransferId, CreateTransferCommand> posted = new HashMap<>();
  private final List<CreateTransferCommand> transfersCreated = new ArrayList<>();
  private LedgerRejectionReason rejectNextTransferWith;

  /** Opens the account (if not already) and sets its unit balance directly. */
  void givenUnvestedBalance(AccountId id, Units balance) {
    openedAccounts.add(id);
    unitBalances.put(id, balance);
  }

  boolean accountOpened(AccountId id) {
    return openedAccounts.contains(id);
  }

  Units balanceOf(AccountId id) {
    return unitBalances.getOrDefault(id, Units.of(0));
  }

  List<CreateTransferCommand> transfersCreated() {
    return List.copyOf(transfersCreated);
  }

  void rejectNextTransferWith(LedgerRejectionReason reason) {
    this.rejectNextTransferWith = reason;
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    openedAccounts.add(cmd.id());
    unitBalances.putIfAbsent(cmd.id(), Units.of(0));
    return cmd.id();
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    if (!openedAccounts.contains(id)) {
      return Optional.empty();
    }
    return Optional.of(
        new LedgerAccount.Unit(
            id,
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
            true,
            1001L,
            unitBalances.getOrDefault(id, Units.of(0))));
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    if (posted.containsKey(cmd.id())) {
      return new TransferResult.AlreadyExists(cmd.id());
    }
    if (rejectNextTransferWith != null) {
      return new TransferResult.Rejected(cmd.id(), rejectNextTransferWith);
    }
    if (cmd instanceof CreateTransferCommand.Unit unit) {
      Units debited = unitBalances.getOrDefault(unit.debitAccountId(), Units.of(0));
      Units credited = unitBalances.getOrDefault(unit.creditAccountId(), Units.of(0));
      unitBalances.put(unit.debitAccountId(), debited.minus(unit.amount()));
      unitBalances.put(unit.creditAccountId(), credited.plus(unit.amount()));
    }
    posted.put(cmd.id(), cmd);
    transfersCreated.add(cmd);
    return new TransferResult.Created(cmd.id(), Instant.EPOCH);
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("VestingService only ever creates a single transfer");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("VestingService never posts a linked chain");
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    throw new UnsupportedOperationException("not used");
  }
}
