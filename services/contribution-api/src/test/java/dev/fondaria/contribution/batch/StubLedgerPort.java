package dev.fondaria.contribution.batch;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferQuery;
import dev.fondaria.ledger.TransferResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link LedgerPort} holding whatever transfers a test says it holds.
 *
 * <p>The gate's whole job is to believe the ledger and nothing else, so what a double has to model
 * here is the ledger disagreeing with PostgreSQL: a receipt row whose transfer is absent, or is
 * present but is some other movement. A double that answered every lookup with the amount the test
 * expected would let a gate that read the database column instead pass unchanged.
 *
 * <p>It is not a second {@code LedgerPort} implementation in ADR-0008's sense — that ban is about
 * production code, and the one implementation is still {@code TigerBeetleLedgerAdapter}. Nothing
 * asserting a balance uses this: those assertions run against a real TigerBeetle.
 */
final class StubLedgerPort implements LedgerPort {

  private final Map<TransferId, LedgerTransfer> transfers = new LinkedHashMap<>();

  /** Records a {@code CASH_RECEIPT} crediting {@code employerCollections}, as a real post would. */
  void givenCashReceipt(TransferId id, AccountId employerCollections, long cents) {
    givenTransfer(id, employerCollections, cents, LedgerCodes.TransferCode.CASH_RECEIPT);
  }

  void givenTransfer(
      TransferId id, AccountId creditAccount, long cents, LedgerCodes.TransferCode code) {
    transfers.put(
        id,
        new LedgerTransfer.Cash(
            id,
            AccountId.of(1L, 1L),
            creditAccount,
            code,
            Money.of(cents, CurrencyCode.MXN),
            Instant.EPOCH,
            null,
            ExternalReference.NONE));
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    return Optional.ofNullable(transfers.get(id));
  }

  /**
   * Accepts and returns the id. Account provisioning belongs to Task 2b.1 and is exercised here
   * only because registering an organization does it; the gate itself never opens an account.
   */
  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    return cmd.id();
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    throw new UnsupportedOperationException("not used");
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    throw new UnsupportedOperationException("the gate never writes to the ledger");
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("the gate never writes to the ledger");
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    throw new UnsupportedOperationException("the gate never writes to the ledger");
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    throw new UnsupportedOperationException("not used");
  }
}
