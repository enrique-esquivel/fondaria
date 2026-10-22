package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Task 2.2a acceptance criteria: cluster-by-fund resolution and cross-cluster rejection. */
class ClusterAwareLedgerPortTest {

  private static final ClusterId PRIMARY = ClusterId.of("primary");
  private static final ClusterId SECONDARY = ClusterId.of("secondary");

  private static final long FUND_A_LEDGER_ID = 1001L;
  private static final long FUND_B_LEDGER_ID = 1002L;

  private static final AccountId DEBIT = AccountId.of(0L, 1L);
  private static final AccountId CREDIT = AccountId.of(0L, 2L);

  // -----------------------------------------------------------------------------------------
  // Single-cluster v1: behaves identically to calling the delegate directly.
  // -----------------------------------------------------------------------------------------

  @Test
  void singleClusterCreateAccountReachesTheOneDelegateUnchanged() {
    FakeLedgerPort delegate = new FakeLedgerPort();
    ClusterAwareLedgerPort port = ClusterAwareLedgerPort.singleCluster(PRIMARY, delegate);

    CreateAccountCommand cmd =
        new CreateAccountCommand(
            DEBIT, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING);
    AccountId result = port.createAccount(cmd);

    assertThat(result).isEqualTo(DEBIT);
    assertThat(delegate.createdAccounts).containsExactly(cmd);
  }

  @Test
  void singleClusterCreateTransferReachesTheOneDelegateUnchanged() {
    FakeLedgerPort delegate = new FakeLedgerPort();
    ClusterAwareLedgerPort port = ClusterAwareLedgerPort.singleCluster(PRIMARY, delegate);

    CreateTransferCommand cmd = cashTransfer(DEBIT, CREDIT);
    TransferResult result = port.createTransfer(cmd);

    assertThat(result).isInstanceOf(TransferResult.Created.class);
    assertThat(delegate.transferBatches).containsExactly(List.of(cmd));
  }

  @Test
  void singleClusterLookupAccountReachesTheOneDelegateUnchanged() {
    FakeLedgerPort delegate = new FakeLedgerPort();
    LedgerAccount.Cash account =
        new LedgerAccount.Cash(
            DEBIT, LedgerCodes.AccountCode.BANK_CLEARING, false, Money.of(0L, CurrencyCode.MXN));
    delegate.accountsById.put(DEBIT, account);
    ClusterAwareLedgerPort port = ClusterAwareLedgerPort.singleCluster(PRIMARY, delegate);

    assertThat(port.lookupAccount(DEBIT)).contains(account);
  }

  // -----------------------------------------------------------------------------------------
  // Cross-cluster rejection.
  // -----------------------------------------------------------------------------------------

  @Test
  void createTransfersAcrossTwoClustersIsRejectedWholesaleAndReachesNeitherDelegate() {
    FakeLedgerPort primaryDelegate = new FakeLedgerPort();
    FakeLedgerPort secondaryDelegate = new FakeLedgerPort();
    ClusterAwareLedgerPort port = twoClusterPort(primaryDelegate, secondaryDelegate);

    CreateTransferCommand fundALeg = unitTransfer(FUND_A_LEDGER_ID);
    CreateTransferCommand fundBLeg = unitTransfer(FUND_B_LEDGER_ID);

    List<TransferResult> results = port.createTransfers(List.of(fundALeg, fundBLeg));

    assertThat(results).hasSize(2);
    for (TransferResult result : results) {
      assertThat(result).isInstanceOf(TransferResult.Rejected.class);
      assertThat(((TransferResult.Rejected) result).reason())
          .isEqualTo(LedgerRejectionReason.CROSS_CLUSTER);
    }
    assertThat(primaryDelegate.transferBatches).isEmpty();
    assertThat(secondaryDelegate.transferBatches).isEmpty();
  }

  @Test
  void createLinkedChainAcrossTwoClustersIsRejectedWholesaleAndReachesNeitherDelegate() {
    FakeLedgerPort primaryDelegate = new FakeLedgerPort();
    FakeLedgerPort secondaryDelegate = new FakeLedgerPort();
    ClusterAwareLedgerPort port = twoClusterPort(primaryDelegate, secondaryDelegate);

    CreateTransferCommand fundALeg = unitTransfer(FUND_A_LEDGER_ID);
    CreateTransferCommand fundBLeg = unitTransfer(FUND_B_LEDGER_ID);

    List<TransferResult> results = port.createLinkedChain(List.of(fundALeg, fundBLeg));

    assertThat(results).hasSize(2);
    for (TransferResult result : results) {
      assertThat(((TransferResult.Rejected) result).reason())
          .isEqualTo(LedgerRejectionReason.CROSS_CLUSTER);
    }
    assertThat(primaryDelegate.linkedChains).isEmpty();
    assertThat(secondaryDelegate.linkedChains).isEmpty();
  }

  @Test
  void createTransfersWithinOneClusterDelegatesNormallyEvenWithTwoClustersConfigured() {
    FakeLedgerPort primaryDelegate = new FakeLedgerPort();
    FakeLedgerPort secondaryDelegate = new FakeLedgerPort();
    ClusterAwareLedgerPort port = twoClusterPort(primaryDelegate, secondaryDelegate);

    CreateTransferCommand legOne = unitTransfer(FUND_A_LEDGER_ID);
    CreateTransferCommand legTwo = unitTransfer(FUND_A_LEDGER_ID);

    List<TransferResult> results = port.createTransfers(List.of(legOne, legTwo));

    assertThat(results).allMatch(TransferResult.Created.class::isInstance);
    assertThat(primaryDelegate.transferBatches).containsExactly(List.of(legOne, legTwo));
    assertThat(secondaryDelegate.transferBatches).isEmpty();
  }

  // -----------------------------------------------------------------------------------------
  // Fan-out reads: AccountId/TransferId carry no ledger id, so lookups try every cluster.
  // -----------------------------------------------------------------------------------------

  @Test
  void lookupAccountFansOutAndReturnsTheFirstMatchAcrossClusters() {
    FakeLedgerPort primaryDelegate = new FakeLedgerPort();
    FakeLedgerPort secondaryDelegate = new FakeLedgerPort();
    LedgerAccount.Cash account =
        new LedgerAccount.Cash(
            CREDIT, LedgerCodes.AccountCode.FUND_CASH, false, Money.of(500L, CurrencyCode.MXN));
    secondaryDelegate.accountsById.put(CREDIT, account);
    ClusterAwareLedgerPort port = twoClusterPort(primaryDelegate, secondaryDelegate);

    assertThat(port.lookupAccount(CREDIT)).contains(account);
  }

  @Test
  void lookupAccountReturnsEmptyWhenNoClusterHasIt() {
    ClusterAwareLedgerPort port = twoClusterPort(new FakeLedgerPort(), new FakeLedgerPort());

    assertThat(port.lookupAccount(CREDIT)).isEmpty();
  }

  /**
   * Row 26p: a list of accounts spanning clusters is split, one delegate request per cluster, and
   * the ids come back in the caller's order. Not rejected the way a cross-cluster transfer batch
   * is: account creation carries no atomicity a split could break.
   */
  @Test
  void createAccountsSplitsByClusterOneRequestEachAndKeepsTheCallersOrder() {
    FakeLedgerPort primary = new FakeLedgerPort();
    FakeLedgerPort secondary = new FakeLedgerPort();
    ClusterAwareLedgerPort port = twoClusterPort(primary, secondary);

    CreateAccountCommand cashOnPrimary =
        new CreateAccountCommand(
            AccountId.of(0L, 11L), LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.FUND_CASH);
    CreateAccountCommand fundBFirst =
        new CreateAccountCommand(
            AccountId.of(0L, 12L), FUND_B_LEDGER_ID, LedgerCodes.AccountCode.UNITS_IN_ISSUE);
    CreateAccountCommand fundAOnPrimary =
        new CreateAccountCommand(
            AccountId.of(0L, 13L),
            FUND_A_LEDGER_ID,
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);
    CreateAccountCommand fundBSecond =
        new CreateAccountCommand(
            AccountId.of(0L, 14L),
            FUND_B_LEDGER_ID,
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);

    List<AccountId> ids =
        port.createAccounts(List.of(cashOnPrimary, fundBFirst, fundAOnPrimary, fundBSecond));

    assertThat(ids)
        .containsExactly(
            cashOnPrimary.id(), fundBFirst.id(), fundAOnPrimary.id(), fundBSecond.id());
    assertThat(primary.accountBatches).containsExactly(List.of(cashOnPrimary, fundAOnPrimary));
    assertThat(secondary.accountBatches).containsExactly(List.of(fundBFirst, fundBSecond));
  }

  // -----------------------------------------------------------------------------------------
  // Configuration validation.
  // -----------------------------------------------------------------------------------------

  @Test
  void constructorRejectsAnEmptyDelegateMap() {
    assertThatThrownBy(
            () ->
                new ClusterAwareLedgerPort(
                    ConfiguredLedgerClusterResolver.singleCluster(PRIMARY), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aResolvedClusterWithNoConfiguredDelegateFailsFast() {
    LedgerClusterResolver misconfiguredResolver = ledgerId -> SECONDARY;
    ClusterAwareLedgerPort port =
        new ClusterAwareLedgerPort(misconfiguredResolver, Map.of(PRIMARY, new FakeLedgerPort()));

    assertThatThrownBy(
            () ->
                port.createAccount(
                    new CreateAccountCommand(
                        DEBIT, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("secondary");
  }

  // -----------------------------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------------------------

  private static ClusterAwareLedgerPort twoClusterPort(
      FakeLedgerPort primaryDelegate, FakeLedgerPort secondaryDelegate) {
    LedgerClusterResolver resolver =
        new ConfiguredLedgerClusterResolver(PRIMARY, Map.of(FUND_B_LEDGER_ID, SECONDARY));
    return new ClusterAwareLedgerPort(
        resolver, Map.of(PRIMARY, primaryDelegate, SECONDARY, secondaryDelegate));
  }

  private static CreateTransferCommand.Cash cashTransfer(AccountId debit, AccountId credit) {
    return CreateTransferCommand.Cash.single(
        TransferId.of(0L, 1L),
        debit,
        credit,
        LedgerCodes.TransferCode.CASH_RECEIPT,
        Money.of(1_000L, CurrencyCode.MXN));
  }

  private static CreateTransferCommand.Unit unitTransfer(long fundLedgerId) {
    return CreateTransferCommand.Unit.single(
        TransferId.of(0L, fundLedgerId),
        DEBIT,
        CREDIT,
        fundLedgerId,
        LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
        Units.of(100_00000000L));
  }

  /** Minimal in-memory {@link LedgerPort} test double; records every call it receives. */
  private static final class FakeLedgerPort implements LedgerPort {
    final List<CreateAccountCommand> createdAccounts = new ArrayList<>();
    final List<List<CreateAccountCommand>> accountBatches = new ArrayList<>();
    final List<List<CreateTransferCommand>> transferBatches = new ArrayList<>();
    final List<List<CreateTransferCommand>> linkedChains = new ArrayList<>();
    final Map<AccountId, LedgerAccount> accountsById = new HashMap<>();

    @Override
    public AccountId createAccount(CreateAccountCommand cmd) {
      createdAccounts.add(cmd);
      return cmd.id();
    }

    @Override
    public List<AccountId> createAccounts(List<CreateAccountCommand> cmds) {
      accountBatches.add(List.copyOf(cmds));
      createdAccounts.addAll(cmds);
      return cmds.stream().map(CreateAccountCommand::id).toList();
    }

    @Override
    public Optional<LedgerAccount> lookupAccount(AccountId id) {
      return Optional.ofNullable(accountsById.get(id));
    }

    @Override
    public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
      List<LedgerAccount> out = new ArrayList<>();
      for (AccountId id : ids) {
        LedgerAccount account = accountsById.get(id);
        if (account != null) {
          out.add(account);
        }
      }
      return out;
    }

    @Override
    public TransferResult createTransfer(CreateTransferCommand cmd) {
      return createTransfers(List.of(cmd)).get(0);
    }

    @Override
    public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
      transferBatches.add(cmds);
      List<TransferResult> out = new ArrayList<>();
      for (CreateTransferCommand cmd : cmds) {
        out.add(new TransferResult.Created(cmd.id(), Instant.EPOCH));
      }
      return out;
    }

    @Override
    public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
      linkedChains.add(cmds);
      List<TransferResult> out = new ArrayList<>();
      for (CreateTransferCommand cmd : cmds) {
        out.add(new TransferResult.Created(cmd.id(), Instant.EPOCH));
      }
      return out;
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
}
