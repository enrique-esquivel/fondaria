package dev.fondaria.ledger.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.testing.TigerBeetleFixture;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.2a acceptance criteria: "single-cluster v1 configuration behaves identically to today."
 * Wraps the real, already-merged {@link TigerBeetleLedgerAdapter} (Task 2.4) with {@link
 * ClusterAwareLedgerPort#singleCluster} and re-runs a representative slice of {@link
 * TigerBeetleLedgerAdapterTest}'s scenarios through the wrapper, against the same TigerBeetle
 * container. {@link TigerBeetleLedgerAdapterTest} itself is untouched.
 */
class ClusterAwareLedgerPortTigerBeetleIntegrationTest {

  private static final AtomicLong FUND_LEDGER_SEQ = new AtomicLong(3_000);
  private static final ClusterId PRIMARY = ClusterId.of("primary");

  private static Client client;
  private static LedgerPort port;

  @BeforeAll
  static void setUpAll() {
    client = TigerBeetleFixture.getInstance().getClient();
    TigerBeetleLedgerAdapter adapter =
        new TigerBeetleLedgerAdapter(client, Duration.ofSeconds(5), ObservationRegistry.create());
    port = ClusterAwareLedgerPort.singleCluster(PRIMARY, adapter);
  }

  @AfterAll
  static void tearDownAll() {
    client.close();
  }

  @Test
  void createsCashAndUnitAccountsTransfersBothAndReadsBalancesBack() {
    AccountId bankClearing = freshAccountId();
    AccountId employerCollections = freshAccountId();
    port.createAccount(
        new CreateAccountCommand(
            bankClearing, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    port.createAccount(
        new CreateAccountCommand(
            employerCollections,
            LedgerCodes.CASH_LEDGER_ID,
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));

    Money remittance = Money.of(750_000L, CurrencyCode.MXN);
    TransferResult cashResult =
        port.createTransfer(
            CreateTransferCommand.Cash.single(
                freshTransferId(),
                bankClearing,
                employerCollections,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                remittance));
    assertThat(cashResult).isInstanceOf(TransferResult.Created.class);

    LedgerAccount.Cash employerCollectionsAccount =
        (LedgerAccount.Cash) port.lookupAccount(employerCollections).orElseThrow();
    assertThat(employerCollectionsAccount.balance()).isEqualTo(remittance);

    long fundLedgerId = FUND_LEDGER_SEQ.incrementAndGet();
    AccountId unitsInIssue = freshAccountId();
    AccountId participantHoldings = freshAccountId();
    port.createAccount(
        new CreateAccountCommand(
            unitsInIssue, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    port.createAccount(
        new CreateAccountCommand(
            participantHoldings,
            fundLedgerId,
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));

    Units allotment = Units.of(75_000_000_000L);
    TransferResult unitResult =
        port.createTransfer(
            CreateTransferCommand.Unit.single(
                freshTransferId(),
                unitsInIssue,
                participantHoldings,
                fundLedgerId,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                allotment));
    assertThat(unitResult).isInstanceOf(TransferResult.Created.class);

    LedgerAccount.Unit unitsInIssueAccount =
        (LedgerAccount.Unit) port.lookupAccount(unitsInIssue).orElseThrow();
    LedgerAccount.Unit participantHoldingsAccount =
        (LedgerAccount.Unit) port.lookupAccount(participantHoldings).orElseThrow();
    assertThat(unitsInIssueAccount.balance()).isEqualTo(allotment);
    assertThat(participantHoldingsAccount.balance()).isEqualTo(allotment);
  }

  @Test
  void sameTransferIdTwiceReturnsAlreadyExistsAndDoesNotMoveTwice() {
    AccountId debit = freshAccountId();
    AccountId credit = freshAccountId();
    createBankClearingPair(debit, credit);

    TransferId transferId = freshTransferId();
    Money amount = Money.of(10_000L, CurrencyCode.MXN);
    CreateTransferCommand.Cash cmd =
        CreateTransferCommand.Cash.single(
            transferId, debit, credit, LedgerCodes.TransferCode.CASH_RECEIPT, amount);

    assertThat(port.createTransfer(cmd)).isInstanceOf(TransferResult.Created.class);
    TransferResult second = port.createTransfer(cmd);
    assertThat(second).isInstanceOf(TransferResult.AlreadyExists.class);

    LedgerAccount.Cash creditAccount =
        (LedgerAccount.Cash) port.lookupAccount(credit).orElseThrow();
    assertThat(creditAccount.balance()).isEqualTo(amount);
  }

  @Test
  void linkedChainWhereLegTwoFailsLeavesZeroLegsApplied() {
    AccountId accountA = freshAccountId();
    AccountId accountB = freshAccountId();
    createBankClearingPair(accountA, accountB);

    Money amount = Money.of(500L, CurrencyCode.MXN);
    CreateTransferCommand leg0 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountA, accountB, LedgerCodes.TransferCode.CASH_RECEIPT, amount);
    CreateTransferCommand leg1 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountB, accountB, LedgerCodes.TransferCode.CASH_RECEIPT, amount);
    CreateTransferCommand leg2 =
        CreateTransferCommand.Cash.single(
            freshTransferId(), accountB, accountA, LedgerCodes.TransferCode.CASH_RECEIPT, amount);

    List<TransferResult> results = port.createLinkedChain(List.of(leg0, leg1, leg2));
    assertThat(results).hasSize(3);
    assertThat(((TransferResult.Rejected) results.get(1)).reason())
        .isEqualTo(LedgerRejectionReason.SELF_TRANSFER);

    LedgerAccount.Cash accountAState =
        (LedgerAccount.Cash) port.lookupAccount(accountA).orElseThrow();
    LedgerAccount.Cash accountBState =
        (LedgerAccount.Cash) port.lookupAccount(accountB).orElseThrow();
    assertThat(accountAState.balance()).isEqualTo(Money.of(0L, CurrencyCode.MXN));
    assertThat(accountBState.balance()).isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  private static void createBankClearingPair(AccountId debit, AccountId credit) {
    port.createAccount(
        new CreateAccountCommand(
            debit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    port.createAccount(
        new CreateAccountCommand(
            credit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));
  }

  private static AccountId freshAccountId() {
    UUID uuid = UUID.randomUUID();
    return AccountId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  private static TransferId freshTransferId() {
    UUID uuid = UUID.randomUUID();
    return TransferId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }
}
