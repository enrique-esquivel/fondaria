package dev.fondaria.ledger.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Task 2.6: the {@link LedgerPort} contract, pinned once against any conforming implementation.
 * Written entirely against {@code libs/ledger-client} domain types — no vendor type appears here —
 * so a future adapter can extend this class unchanged and be exercised by the same cases the
 * TigerBeetle adapter (Task 2.4) is exercised by below.
 *
 * <p>A subclass supplies the {@link LedgerPort} under test and a source of fresh, never-reused fund
 * ledger ids (accounts and transfers use fresh random ids per test since the ledger is append-only
 * and cannot be reset — see {@code TigerBeetleFixture}'s own warning).
 *
 * <p>Cross-cluster rejection (Task 2.2a) is not a case here: it is a property of {@code
 * ClusterAwareLedgerPort} routing, meaningless against a bare adapter with only one ledger-id
 * space. It is covered separately, only by the cluster-wrapped subclass.
 */
abstract class LedgerPortConformanceSuite {

  protected abstract LedgerPort port();

  /** A fund unit-ledger id never used by another test in this JVM run. */
  protected abstract long freshFundLedgerId();

  // -----------------------------------------------------------------------------------------
  // Account creation and lookup
  // -----------------------------------------------------------------------------------------

  @Test
  void createsCashAndUnitAccountsAndLooksThemUpIndividuallyAndInBatch() {
    AccountId bankClearing = freshAccountId();
    AccountId employerCollections = freshAccountId();
    createCashAccount(bankClearing, LedgerCodes.AccountCode.BANK_CLEARING);
    createCashAccount(employerCollections, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS);

    long fundLedgerId = freshFundLedgerId();
    AccountId unitsInIssue = freshAccountId();
    createUnitAccount(unitsInIssue, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE);

    LedgerAccount.Cash cash = (LedgerAccount.Cash) port().lookupAccount(bankClearing).orElseThrow();
    assertThat(cash.id()).isEqualTo(bankClearing);
    assertThat(cash.accountCode()).isEqualTo(LedgerCodes.AccountCode.BANK_CLEARING);
    assertThat(cash.debitsMustNotExceedCredits()).isFalse();
    assertThat(cash.balance()).isEqualTo(Money.of(0L, CurrencyCode.MXN));

    LedgerAccount.Unit unit = (LedgerAccount.Unit) port().lookupAccount(unitsInIssue).orElseThrow();
    assertThat(unit.id()).isEqualTo(unitsInIssue);
    assertThat(unit.fundLedgerId()).isEqualTo(fundLedgerId);
    assertThat(unit.balance()).isEqualTo(Units.of(0L));

    List<LedgerAccount> batch =
        port().lookupAccounts(List.of(bankClearing, employerCollections, unitsInIssue));
    assertThat(batch)
        .hasSize(3)
        .extracting(LedgerAccount::id)
        .containsExactlyInAnyOrder(bankClearing, employerCollections, unitsInIssue);
  }

  @Test
  void lookupAccountForAnUnknownIdIsEmpty() {
    assertThat(port().lookupAccount(freshAccountId())).isEmpty();
  }

  // -----------------------------------------------------------------------------------------
  // Single and batch transfer
  // -----------------------------------------------------------------------------------------

  @Test
  void singleCashTransferMovesBalanceOnBothLegs() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    Money amount = Money.of(12_345L, CurrencyCode.MXN);
    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount));

    assertThat(result).isInstanceOf(TransferResult.Created.class);
    assertThat(cashBalance(debit)).isEqualTo(amount.negated());
    assertThat(cashBalance(credit)).isEqualTo(amount);
  }

  @Test
  void singleUnitTransferMovesBalanceOnBothLegs() {
    long fundLedgerId = freshFundLedgerId();
    AccountId unitsInIssue = freshAccountId();
    AccountId holdings = freshAccountId();
    createUnitAccount(unitsInIssue, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE);
    createUnitAccount(holdings, fundLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);

    Units amount = Units.of(500_00000000L);
    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Unit.single(
                    freshTransferId(),
                    unitsInIssue,
                    holdings,
                    fundLedgerId,
                    LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                    amount));

    assertThat(result).isInstanceOf(TransferResult.Created.class);
    // Sigma participant units = units in issue (AGENTS.md Section 2) - both sides report the same
    // non-negative magnitude despite UNITS_IN_ISSUE being a contra account.
    assertThat(unitBalance(unitsInIssue)).isEqualTo(amount);
    assertThat(unitBalance(holdings)).isEqualTo(amount);
  }

  @Test
  void batchTransferAppliesEveryLeg() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    Money amount = Money.of(100L, CurrencyCode.MXN);
    int count = 10;
    List<CreateTransferCommand> cmds = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      cmds.add(
          CreateTransferCommand.Cash.single(
              freshTransferId(), debit, credit, LedgerCodes.TransferCode.CASH_RECEIPT, amount));
    }

    List<TransferResult> results = port().createTransfers(cmds);
    assertThat(results).hasSize(count);
    assertThat(results).allMatch(TransferResult.Created.class::isInstance);
    assertThat(cashBalance(credit)).isEqualTo(Money.of(count * 100L, CurrencyCode.MXN));
  }

  // -----------------------------------------------------------------------------------------
  // Duplicate id
  // -----------------------------------------------------------------------------------------

  @Test
  void duplicateTransferIdReturnsAlreadyExistsAndDoesNotMoveTwice() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId id = freshTransferId();
    Money amount = Money.of(500L, CurrencyCode.MXN);
    CreateTransferCommand cmd =
        CreateTransferCommand.Cash.single(
            id, debit, credit, LedgerCodes.TransferCode.CASH_RECEIPT, amount);

    assertThat(port().createTransfer(cmd)).isInstanceOf(TransferResult.Created.class);
    TransferResult second = port().createTransfer(cmd);
    assertThat(second).isInstanceOf(TransferResult.AlreadyExists.class);
    assertThat(second.id()).isEqualTo(id);
    assertThat(cashBalance(credit)).isEqualTo(amount);
  }

  // -----------------------------------------------------------------------------------------
  // Insufficient balance - cash and units
  // -----------------------------------------------------------------------------------------

  @Test
  void insufficientUnitBalanceIsRejectedAndLeavesBalancesUntouched() {
    long fundLedgerId = freshFundLedgerId();
    AccountId unitsInIssue = freshAccountId();
    AccountId holdings = freshAccountId();
    createUnitAccount(unitsInIssue, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE);
    createUnitAccount(holdings, fundLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);

    Units held = Units.of(100_00000000L);
    port()
        .createTransfer(
            CreateTransferCommand.Unit.single(
                freshTransferId(),
                unitsInIssue,
                holdings,
                fundLedgerId,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                held));

    TransferResult redemption =
        port()
            .createTransfer(
                CreateTransferCommand.Unit.single(
                    freshTransferId(),
                    holdings,
                    unitsInIssue,
                    fundLedgerId,
                    LedgerCodes.TransferCode.REDEMPTION,
                    Units.of(150_00000000L)));

    assertThat(redemption).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) redemption).reason())
        .isEqualTo(LedgerRejectionReason.INSUFFICIENT_UNITS);
    assertThat(unitBalance(holdings)).isEqualTo(held);
  }

  /**
   * The chart of accounts (Task 2.1) has no MXN cash account carrying {@code
   * debits_must_not_exceed_credits} — suspense/clearing accounts are deliberately unrestricted
   * (docs/chart-of-accounts.md Section 5); only the two participant-holdings unit accounts carry
   * the flag. {@link LedgerPort} itself does not tie a flag to a specific ledger, so this test
   * opens a cash-ledger account under a code that does carry it purely to exercise the {@code
   * INSUFFICIENT_FUNDS} branch of the port's rejection-reason mapping, which is otherwise
   * unreachable with today's chart. The mapping is keyed on the transfer's quantity kind (cash vs.
   * unit), not on the account code, so this is a faithful exercise of the contract.
   */
  @Test
  void insufficientCashBalanceIsRejectedAndLeavesBalancesUntouched() {
    AccountId source = freshAccountId();
    AccountId restricted = freshAccountId();
    createCashAccount(source, LedgerCodes.AccountCode.BANK_CLEARING);
    createCashAccount(restricted, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);

    Money held = Money.of(1_000L, CurrencyCode.MXN);
    port()
        .createTransfer(
            CreateTransferCommand.Cash.single(
                freshTransferId(),
                source,
                restricted,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                held));

    TransferResult overDraw =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    restricted,
                    source,
                    LedgerCodes.TransferCode.REDEMPTION,
                    Money.of(1_500L, CurrencyCode.MXN)));

    assertThat(overDraw).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) overDraw).reason())
        .isEqualTo(LedgerRejectionReason.INSUFFICIENT_FUNDS);
    assertThat(cashBalance(restricted)).isEqualTo(held);
  }

  // -----------------------------------------------------------------------------------------
  // Cross-ledger rejection
  // -----------------------------------------------------------------------------------------

  @Test
  void crossLedgerTransferAttemptIsRejected() {
    AccountId cashAccount = freshAccountId();
    createCashAccount(cashAccount, LedgerCodes.AccountCode.FUND_CASH);

    long fundLedgerId = freshFundLedgerId();
    AccountId unitAccount = freshAccountId();
    createUnitAccount(unitAccount, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE);

    // A Cash command always carries ledger=484 on the wire; pointing its credit leg at an account
    // actually registered on the fund's unit ledger is precisely the cross-ledger attempt.
    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    cashAccount,
                    unitAccount,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(100L, CurrencyCode.MXN)));

    assertThat(result).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) result).reason())
        .isEqualTo(LedgerRejectionReason.CROSS_LEDGER);
  }

  // -----------------------------------------------------------------------------------------
  // Zero and negative amount rejection
  // -----------------------------------------------------------------------------------------

  @Test
  void zeroAmountTransferIsRejected() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(0L, CurrencyCode.MXN)));

    assertThat(((TransferResult.Rejected) result).reason())
        .isEqualTo(LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);
  }

  @Test
  void negativeAmountTransferIsRejected() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(-100L, CurrencyCode.MXN)));

    assertThat(((TransferResult.Rejected) result).reason())
        .isEqualTo(LedgerRejectionReason.ZERO_OR_NEGATIVE_AMOUNT);
  }

  // -----------------------------------------------------------------------------------------
  // Self-transfer rejection
  // -----------------------------------------------------------------------------------------

  @Test
  void selfTransferIsRejected() {
    AccountId account = freshAccountId();
    createCashAccount(account, LedgerCodes.AccountCode.BANK_CLEARING);

    TransferResult result =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    account,
                    account,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(100L, CurrencyCode.MXN)));

    assertThat(result).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) result).reason())
        .isEqualTo(LedgerRejectionReason.SELF_TRANSFER);
  }

  // -----------------------------------------------------------------------------------------
  // Pending lifecycle: post, void, timeout
  // -----------------------------------------------------------------------------------------

  @Test
  void pendingTransferCanBePosted() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId pendingId = freshTransferId();
    Money amount = Money.of(750L, CurrencyCode.MXN);
    TransferResult pendingResult =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.pending(
                    pendingId,
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount,
                    Duration.ofMinutes(1)));
    assertThat(pendingResult).isInstanceOf(TransferResult.Created.class);
    // Not yet posted: the credit account's posted balance is untouched.
    assertThat(cashBalance(credit)).isEqualTo(Money.of(0L, CurrencyCode.MXN));

    TransferResult postResult =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.postPending(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount,
                    pendingId));
    assertThat(postResult).isInstanceOf(TransferResult.Created.class);
    assertThat(cashBalance(credit)).isEqualTo(amount);
  }

  @Test
  void pendingTransferCanBeVoided() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId pendingId = freshTransferId();
    Money amount = Money.of(300L, CurrencyCode.MXN);
    port()
        .createTransfer(
            CreateTransferCommand.Cash.pending(
                pendingId,
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                amount,
                Duration.ofMinutes(1)));

    TransferResult voidResult =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.voidPending(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount,
                    pendingId));
    assertThat(voidResult).isInstanceOf(TransferResult.Created.class);
    assertThat(cashBalance(credit)).isEqualTo(Money.of(0L, CurrencyCode.MXN));

    // Posting an already-voided pending transfer is rejected.
    TransferResult postAfterVoid =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.postPending(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount,
                    pendingId));
    assertThat(postAfterVoid).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) postAfterVoid).reason())
        .isEqualTo(LedgerRejectionReason.PENDING_TRANSFER_ALREADY_VOIDED);
  }

  @Test
  void pendingTransferExpiresAfterItsTimeout() throws InterruptedException {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId pendingId = freshTransferId();
    Money amount = Money.of(250L, CurrencyCode.MXN);
    port()
        .createTransfer(
            CreateTransferCommand.Cash.pending(
                pendingId,
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                amount,
                Duration.ofSeconds(1)));

    Thread.sleep(1_500);

    TransferResult postAfterExpiry =
        port()
            .createTransfer(
                CreateTransferCommand.Cash.postPending(
                    freshTransferId(),
                    debit,
                    credit,
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    amount,
                    pendingId));
    assertThat(postAfterExpiry).isInstanceOf(TransferResult.Rejected.class);
    assertThat(((TransferResult.Rejected) postAfterExpiry).reason())
        .isEqualTo(LedgerRejectionReason.PENDING_TRANSFER_EXPIRED);
    assertThat(cashBalance(credit)).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  // -----------------------------------------------------------------------------------------
  // Linked chain: success and failure
  // -----------------------------------------------------------------------------------------

  @Test
  void linkedChainSucceedsWithEveryLegApplied() {
    AccountId accountA = freshAccountId();
    AccountId accountB = freshAccountId();
    createBankClearingPair(accountA, accountB);

    Money amount = Money.of(400L, CurrencyCode.MXN);
    CreateTransferCommand leg0 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountA, accountB, LedgerCodes.TransferCode.CASH_RECEIPT, amount);
    CreateTransferCommand leg1 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountB, accountA, LedgerCodes.TransferCode.CASH_RECEIPT, amount);

    List<TransferResult> results = port().createLinkedChain(List.of(leg0, leg1));
    assertThat(results).hasSize(2);
    assertThat(results).allMatch(TransferResult.Created.class::isInstance);
    assertThat(cashBalance(accountA)).isEqualTo(Money.of(0L, CurrencyCode.MXN));
    assertThat(cashBalance(accountB)).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  @Test
  void linkedChainFailureLeavesZeroLegsApplied() {
    AccountId accountA = freshAccountId();
    AccountId accountB = freshAccountId();
    createBankClearingPair(accountA, accountB);

    Money amount = Money.of(500L, CurrencyCode.MXN);
    CreateTransferCommand leg0 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountA, accountB, LedgerCodes.TransferCode.CASH_RECEIPT, amount);
    // Self-transfer: rejected regardless of balances.
    CreateTransferCommand leg1 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountB, accountB, LedgerCodes.TransferCode.CASH_RECEIPT, amount);
    CreateTransferCommand leg2 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountB, accountA, LedgerCodes.TransferCode.CASH_RECEIPT, amount);

    List<TransferResult> results = port().createLinkedChain(List.of(leg0, leg1, leg2));
    assertThat(results).hasSize(3);
    assertThat(results).allMatch(TransferResult.Rejected.class::isInstance);
    assertThat(cashBalance(accountA)).isEqualTo(Money.of(0L, CurrencyCode.MXN));
    assertThat(cashBalance(accountB)).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  // -----------------------------------------------------------------------------------------
  // Ordering by timestamp and history pagination
  // -----------------------------------------------------------------------------------------

  @Test
  void accountTransfersAreOrderedByTimestampDescendingByDefault() {
    AccountId source = freshAccountId();
    AccountId account = freshAccountId();
    createBankClearingPair(source, account);

    List<TransferId> createdInOrder = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      TransferId id = freshTransferId();
      port()
          .createTransfer(
              CreateTransferCommand.Cash.single(
                  id,
                  source,
                  account,
                  LedgerCodes.TransferCode.CASH_RECEIPT,
                  Money.of(10L, CurrencyCode.MXN)));
      createdInOrder.add(id);
    }

    List<LedgerTransfer> history = port().accountTransfers(account, TransferQuery.firstPage(10));
    List<TransferId> historyIds = history.stream().map(LedgerTransfer::id).toList();
    List<TransferId> expectedDescending = new ArrayList<>(createdInOrder);
    java.util.Collections.reverse(expectedDescending);
    assertThat(historyIds).isEqualTo(expectedDescending);
  }

  @Test
  void accountTransfersHistoryPaginatesWithCursor() {
    AccountId source = freshAccountId();
    AccountId account = freshAccountId();
    createBankClearingPair(source, account);

    List<TransferId> createdInOrder = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TransferId id = freshTransferId();
      port()
          .createTransfer(
              CreateTransferCommand.Cash.single(
                  id,
                  source,
                  account,
                  LedgerCodes.TransferCode.CASH_RECEIPT,
                  Money.of(10L, CurrencyCode.MXN)));
      createdInOrder.add(id);
    }

    List<TransferId> collected = new ArrayList<>();
    Optional<TransferId> cursor = Optional.empty();
    while (collected.size() < createdInOrder.size()) {
      TransferQuery query =
          cursor.isEmpty()
              ? TransferQuery.firstPage(2)
              : TransferQuery.after(cursor.get(), 2, TransferQuery.SortDirection.DESCENDING);
      List<LedgerTransfer> page = port().accountTransfers(account, query);
      assertThat(page).isNotEmpty();
      for (LedgerTransfer transfer : page) {
        collected.add(transfer.id());
      }
      cursor = Optional.of(page.get(page.size() - 1).id());
    }

    List<TransferId> expectedDescending = new ArrayList<>(createdInOrder);
    java.util.Collections.reverse(expectedDescending);
    assertThat(collected).isEqualTo(expectedDescending);
  }

  // -----------------------------------------------------------------------------------------
  // External reference
  // -----------------------------------------------------------------------------------------

  @Test
  void aCashTransfersExternalReferenceSurvivesTheRoundTrip() {
    // The reference is what ties a movement back to the bank statement line that caused it
    // (ADR-0010). If it does not survive the write, a manually keyed receipt has no tie-out at
    // all, so the round trip is pinned here rather than assumed.
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    ExternalReference reference =
        ExternalReference.derive(UUID.randomUUID(), "SPEI-" + UUID.randomUUID());
    TransferId id = freshTransferId();

    port()
        .createTransfer(
            CreateTransferCommand.Cash.single(
                id,
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                Money.of(750_000L, CurrencyCode.MXN),
                reference));

    LedgerTransfer.Cash posted = (LedgerTransfer.Cash) port().lookupTransfer(id).orElseThrow();
    assertThat(posted.externalReference()).isEqualTo(reference);
    assertThat(posted.externalReference().isPresent()).isTrue();
  }

  @Test
  void aCashTransferWithoutAnExternalReferenceReadsBackAsNone() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId id = freshTransferId();
    port()
        .createTransfer(
            CreateTransferCommand.Cash.single(
                id,
                debit,
                credit,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                Money.of(1_000L, CurrencyCode.MXN)));

    LedgerTransfer.Cash posted = (LedgerTransfer.Cash) port().lookupTransfer(id).orElseThrow();
    assertThat(posted.externalReference()).isEqualTo(ExternalReference.NONE);
    assertThat(posted.externalReference().isPresent()).isFalse();
  }

  // -----------------------------------------------------------------------------------------
  // Shared helpers
  // -----------------------------------------------------------------------------------------

  protected final void createCashAccount(AccountId id, LedgerCodes.AccountCode accountCode) {
    port().createAccount(new CreateAccountCommand(id, LedgerCodes.CASH_LEDGER_ID, accountCode));
  }

  protected final void createUnitAccount(
      AccountId id, long fundLedgerId, LedgerCodes.AccountCode accountCode) {
    port().createAccount(new CreateAccountCommand(id, fundLedgerId, accountCode));
  }

  protected final void createBankClearingPair(AccountId debit, AccountId credit) {
    createCashAccount(debit, LedgerCodes.AccountCode.BANK_CLEARING);
    createCashAccount(credit, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS);
  }

  protected final Money cashBalance(AccountId id) {
    return ((LedgerAccount.Cash) port().lookupAccount(id).orElseThrow()).balance();
  }

  protected final Units unitBalance(AccountId id) {
    return ((LedgerAccount.Unit) port().lookupAccount(id).orElseThrow()).balance();
  }

  protected static AccountId freshAccountId() {
    UUID uuid = UUID.randomUUID();
    return AccountId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  protected static TransferId freshTransferId() {
    UUID uuid = UUID.randomUUID();
    return TransferId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }
}
