package dev.fondaria.ledger.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.ClusterAwareLedgerPort;
import dev.fondaria.ledger.ClusterId;
import dev.fondaria.ledger.ConfiguredLedgerClusterResolver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import dev.fondaria.testing.TigerBeetleFixture;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.6: runs {@link LedgerPortConformanceSuite} against the real {@link
 * TigerBeetleLedgerAdapter} wrapped in {@link ClusterAwareLedgerPort#singleCluster}, proving
 * single-cluster v1 configuration behaves identically to the bare adapter (Task 2.2a's acceptance
 * criterion).
 *
 * <p>Cross-cluster rejection is the one case the shared suite cannot express — it is a property of
 * routing between two configured clusters, meaningless against a single delegate — so it is added
 * here as an extra test, wiring a second {@link ClusterId} for the duration of that one test only.
 */
class ClusterAwareLedgerPortConformanceTest extends LedgerPortConformanceSuite {

  private static final AtomicLong FUND_LEDGER_SEQ = new AtomicLong(7_000);
  private static final ClusterId PRIMARY = ClusterId.of("conformance-primary");
  private static final ClusterId SECONDARY = ClusterId.of("conformance-secondary");

  private static Client client;
  private static TigerBeetleLedgerAdapter adapter;
  private static LedgerPort singleClusterPort;

  @BeforeAll
  static void setUpAll() {
    client = TigerBeetleFixture.getInstance().getClient();
    adapter =
        new TigerBeetleLedgerAdapter(client, Duration.ofSeconds(5), ObservationRegistry.create());
    singleClusterPort = ClusterAwareLedgerPort.singleCluster(PRIMARY, adapter);
  }

  @AfterAll
  static void tearDownAll() {
    client.close();
  }

  @Override
  protected LedgerPort port() {
    return singleClusterPort;
  }

  @Override
  protected long freshFundLedgerId() {
    return FUND_LEDGER_SEQ.incrementAndGet();
  }

  @Test
  void crossClusterBatchIsRejectedWholesaleLeavingNoLegApplied() {
    long fundOnSecondary = freshFundLedgerId();
    // Both cluster ids delegate to the same TigerBeetle container - the rejection happens purely
    // from resolving the two commands to different ClusterIds, before either delegate is called.
    // The cash leg resolves to PRIMARY (the default); the unit leg's fund ledger id is overridden
    // to SECONDARY, which is what makes this one batch span two clusters.
    LedgerPort twoClusterPort =
        new ClusterAwareLedgerPort(
            new ConfiguredLedgerClusterResolver(PRIMARY, Map.of(fundOnSecondary, SECONDARY)),
            Map.of(PRIMARY, adapter, SECONDARY, adapter));

    AccountId cashDebit = freshAccountId();
    AccountId cashCredit = freshAccountId();
    twoClusterPort.createAccount(
        new CreateAccountCommand(
            cashDebit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    twoClusterPort.createAccount(
        new CreateAccountCommand(
            cashCredit, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS));

    AccountId unitsInIssue = freshAccountId();
    AccountId holdings = freshAccountId();
    twoClusterPort.createAccount(
        new CreateAccountCommand(
            unitsInIssue, fundOnSecondary, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    twoClusterPort.createAccount(
        new CreateAccountCommand(
            holdings, fundOnSecondary, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));

    CreateTransferCommand cashLeg =
        CreateTransferCommand.Cash.single(
            freshTransferId(),
            cashDebit,
            cashCredit,
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(100L, CurrencyCode.MXN));
    CreateTransferCommand unitLeg =
        CreateTransferCommand.Unit.single(
            freshTransferId(),
            unitsInIssue,
            holdings,
            fundOnSecondary,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(100_00000000L));

    List<TransferResult> results = twoClusterPort.createTransfers(List.of(cashLeg, unitLeg));
    assertThat(results).hasSize(2);
    for (TransferResult result : results) {
      assertThat(result).isInstanceOf(TransferResult.Rejected.class);
      assertThat(((TransferResult.Rejected) result).reason())
          .isEqualTo(LedgerRejectionReason.CROSS_CLUSTER);
    }

    LedgerAccount.Unit unitAccount =
        (LedgerAccount.Unit) twoClusterPort.lookupAccount(unitsInIssue).orElseThrow();
    assertThat(unitAccount.balance()).isEqualTo(Units.of(0L));
  }
}
