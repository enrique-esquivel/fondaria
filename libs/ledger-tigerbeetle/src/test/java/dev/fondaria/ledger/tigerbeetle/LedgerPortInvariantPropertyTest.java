package dev.fondaria.ledger.tigerbeetle;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigerbeetle.Client;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.testing.TigerBeetleFixture;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Task 2.7: the five properties from {@code docs/ai-tasks/phase-02-ledger.md} — books balance,
 * Sigma(vested + unvested) == units in issue, no negative holdings, replay is a no-op, and reported
 * balances match an independently computed model — checked against the real {@link
 * TigerBeetleLedgerAdapter} (the only {@link LedgerPort} implementation, ADR-0008) over randomly
 * generated sequences of up to 1,000 operations.
 *
 * <p>Each generated try builds a fresh {@link AccountSet} (funds, per-participant vested/unvested
 * holdings, and a couple of cash accounts), resolves the generated operations against an
 * independent in-memory shadow model — clamping vesting/redemption amounts to what the model
 * currently holds, exactly as the real ledger would enforce via {@code
 * debits_must_not_exceed_credits} — and submits the resulting commands as a single batch. All five
 * properties are then checked against that one execution, so the same 500+ generated sequences
 * exercise every property.
 *
 * <p>Properties 1 and 2 are computed from the adapter's own reported balances, not from the shadow
 * model, so a real mapping bug (a skipped credit leg, units issued without debiting {@code
 * units_in_issue}) shows up as a nonzero books-balance or a holdings/issuance mismatch — not just a
 * self-consistent replay of our own bookkeeping.
 */
class LedgerPortInvariantPropertyTest {

  private static final int FUND_COUNT = 2;
  private static final int PARTICIPANT_COUNT = 2;
  private static final int CASH_ACCOUNT_COUNT = 2;
  private static final LedgerCodes.AccountCode[] CASH_ACCOUNT_CODES = {
    LedgerCodes.AccountCode.BANK_CLEARING, LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS
  };

  private static final AtomicLong FUND_LEDGER_SEQ = new AtomicLong(50_000);

  private static Client client;
  private static LedgerPort port;

  @BeforeContainer
  static void setUpAll() {
    client = TigerBeetleFixture.getInstance().getClient();
    port =
        new TigerBeetleLedgerAdapter(client, Duration.ofSeconds(5), ObservationRegistry.create());
  }

  @AfterContainer
  static void tearDownAll() {
    client.close();
  }

  @Property(tries = 500)
  void ledgerInvariantsHoldAcrossRandomOperationSequences(
      @ForAll("operationSequences") List<GeneratedOp> ops) {
    AccountSet accounts = freshAccountSet();
    ShadowModel model = resolveAmounts(ops);
    List<CreateTransferCommand> commands = toCommands(model.resolved(), accounts);
    List<AccountId> allIds = accounts.allAccountIds();

    List<TransferResult> firstRun = port.createTransfers(commands);
    assertThat(firstRun).allMatch(TransferResult.Created.class::isInstance);

    Map<AccountId, LedgerAccount> afterFirst = lookupAll(allIds);

    assertReportedBalancesMatchModel(afterFirst, accounts, model); // property 5
    assertBooksBalancePerLedger(afterFirst, accounts); // property 1
    assertHoldingsSumEqualsUnitsInIssue(afterFirst, accounts); // property 2
    assertNoHoldingsAccountIsNegative(afterFirst, accounts); // property 3

    // Property 4: replaying the exact same batch (same ids) moves nothing a second time.
    List<TransferResult> secondRun = port.createTransfers(commands);
    assertThat(secondRun).allMatch(TransferResult.AlreadyExists.class::isInstance);
    assertThat(lookupAll(allIds)).isEqualTo(afterFirst);
  }

  // -----------------------------------------------------------------------------------------
  // Property assertions
  // -----------------------------------------------------------------------------------------

  private void assertReportedBalancesMatchModel(
      Map<AccountId, LedgerAccount> balances, AccountSet accounts, ShadowModel model) {
    for (int f = 0; f < FUND_COUNT; f++) {
      for (int p = 0; p < PARTICIPANT_COUNT; p++) {
        for (boolean vested : List.of(true, false)) {
          AccountId id = accounts.holdingsAccount(f, p, vested);
          long expected = model.holdings().getOrDefault(new HoldingsKey(f, p, vested), 0L);
          assertThat(((LedgerAccount.Unit) balances.get(id)).balance())
              .isEqualTo(Units.of(expected));
        }
      }
      long expectedIssue = model.unitsInIssue().getOrDefault(f, 0L);
      assertThat(((LedgerAccount.Unit) balances.get(accounts.unitsInIssue.get(f))).balance())
          .isEqualTo(Units.of(expectedIssue));
    }
    for (int c = 0; c < CASH_ACCOUNT_COUNT; c++) {
      long expectedCash = model.cash().getOrDefault(c, 0L);
      assertThat(((LedgerAccount.Cash) balances.get(accounts.cashAccounts.get(c))).balance())
          .isEqualTo(Money.of(expectedCash, CurrencyCode.MXN));
    }
  }

  private void assertBooksBalancePerLedger(
      Map<AccountId, LedgerAccount> balances, AccountSet accounts) {
    for (int f = 0; f < FUND_COUNT; f++) {
      long rawNet = rawNet((LedgerAccount.Unit) balances.get(accounts.unitsInIssue.get(f)));
      for (int p = 0; p < PARTICIPANT_COUNT; p++) {
        rawNet += rawNet((LedgerAccount.Unit) balances.get(accounts.holdingsAccount(f, p, true)));
        rawNet += rawNet((LedgerAccount.Unit) balances.get(accounts.holdingsAccount(f, p, false)));
      }
      assertThat(rawNet).as("fund %d books balance (debits == credits)", f).isZero();
    }
    long cashRawNet =
        accounts.cashAccounts.stream()
            .mapToLong(id -> ((LedgerAccount.Cash) balances.get(id)).balance().cents())
            .sum();
    assertThat(cashRawNet).as("cash ledger books balance (debits == credits)").isZero();
  }

  private void assertHoldingsSumEqualsUnitsInIssue(
      Map<AccountId, LedgerAccount> balances, AccountSet accounts) {
    for (int f = 0; f < FUND_COUNT; f++) {
      long holdingsSum = 0L;
      for (int p = 0; p < PARTICIPANT_COUNT; p++) {
        holdingsSum +=
            ((LedgerAccount.Unit) balances.get(accounts.holdingsAccount(f, p, true)))
                .balance()
                .scaled();
        holdingsSum +=
            ((LedgerAccount.Unit) balances.get(accounts.holdingsAccount(f, p, false)))
                .balance()
                .scaled();
      }
      long unitsInIssueBalance =
          ((LedgerAccount.Unit) balances.get(accounts.unitsInIssue.get(f))).balance().scaled();
      assertThat(holdingsSum)
          .as("fund %d: Sigma(vested + unvested) == units in issue", f)
          .isEqualTo(unitsInIssueBalance);
    }
  }

  private void assertNoHoldingsAccountIsNegative(
      Map<AccountId, LedgerAccount> balances, AccountSet accounts) {
    for (AccountId id : accounts.allHoldingsAccountIds()) {
      assertThat(((LedgerAccount.Unit) balances.get(id)).balance().scaled())
          .isGreaterThanOrEqualTo(0L);
    }
  }

  /**
   * Raw, convention-independent net (creditsPosted - debitsPosted), recovered from {@link
   * LedgerAccount.Unit#balance()} using the same sign rule the adapter itself documents: a
   * debits-must-not-exceed-credits account already reports creditsPosted - debitsPosted, a contra
   * account reports the negation of it.
   */
  private static long rawNet(LedgerAccount.Unit account) {
    long magnitude = account.balance().scaled();
    return account.debitsMustNotExceedCredits() ? magnitude : -magnitude;
  }

  private Map<AccountId, LedgerAccount> lookupAll(List<AccountId> ids) {
    return port.lookupAccounts(ids).stream().collect(Collectors.toMap(LedgerAccount::id, a -> a));
  }

  // -----------------------------------------------------------------------------------------
  // Operation generation
  // -----------------------------------------------------------------------------------------

  @Provide
  Arbitrary<List<GeneratedOp>> operationSequences() {
    Arbitrary<GeneratedOp> op =
        Combinators.combine(
                Arbitraries.of(OpKind.values()),
                Arbitraries.integers().between(0, FUND_COUNT - 1),
                Arbitraries.integers().between(0, PARTICIPANT_COUNT - 1),
                Arbitraries.of(true, false),
                Arbitraries.longs().between(1L, 100_000L),
                Arbitraries.integers().between(0, CASH_ACCOUNT_COUNT - 1),
                Arbitraries.integers().between(0, CASH_ACCOUNT_COUNT - 1))
            .as(GeneratedOp::new);
    return op.list().ofMinSize(1).ofMaxSize(1000);
  }

  private enum OpKind {
    CONTRIBUTION,
    VESTING,
    REDEMPTION,
    CASH_MOVE
  }

  /** One generated instruction, before clamping to what the shadow model currently holds. */
  private record GeneratedOp(
      OpKind kind,
      int fundIndex,
      int participantIndex,
      boolean vested,
      long rawAmount,
      int cashFromIndex,
      int cashToIndex) {}

  /** A {@link GeneratedOp} after clamping - only ops that actually move something survive. */
  private record ResolvedOp(
      OpKind kind,
      int fundIndex,
      int participantIndex,
      boolean vested,
      long amount,
      int cashFrom,
      int cashTo) {}

  private record HoldingsKey(int fundIndex, int participantIndex, boolean vested) {}

  /**
   * The independently computed reference for property 5: what every account's balance must be after
   * {@link #resolved()} is applied, tracked without ever reading the ledger back.
   */
  private record ShadowModel(
      List<ResolvedOp> resolved,
      Map<HoldingsKey, Long> holdings,
      Map<Integer, Long> unitsInIssue,
      Map<Integer, Long> cash) {}

  /**
   * Replays {@code ops} against an in-memory model starting at zero, clamping every
   * vesting/redemption amount to what that model currently holds - the same non-negative constraint
   * {@code debits_must_not_exceed_credits} enforces on the real ledger - and dropping any op that
   * would clamp to zero.
   */
  private ShadowModel resolveAmounts(List<GeneratedOp> ops) {
    Map<HoldingsKey, Long> holdings = new HashMap<>();
    Map<Integer, Long> unitsInIssue = new HashMap<>();
    Map<Integer, Long> cash = new HashMap<>();
    List<ResolvedOp> resolved = new ArrayList<>();

    for (GeneratedOp op : ops) {
      switch (op.kind()) {
        case CONTRIBUTION -> {
          HoldingsKey key = new HoldingsKey(op.fundIndex(), op.participantIndex(), op.vested());
          holdings.merge(key, op.rawAmount(), Long::sum);
          unitsInIssue.merge(op.fundIndex(), op.rawAmount(), Long::sum);
          resolved.add(
              new ResolvedOp(
                  OpKind.CONTRIBUTION,
                  op.fundIndex(),
                  op.participantIndex(),
                  op.vested(),
                  op.rawAmount(),
                  -1,
                  -1));
        }
        case VESTING -> {
          HoldingsKey unvestedKey = new HoldingsKey(op.fundIndex(), op.participantIndex(), false);
          long amount = Math.min(holdings.getOrDefault(unvestedKey, 0L), op.rawAmount());
          if (amount > 0) {
            holdings.merge(unvestedKey, -amount, Long::sum);
            holdings.merge(
                new HoldingsKey(op.fundIndex(), op.participantIndex(), true), amount, Long::sum);
            resolved.add(
                new ResolvedOp(
                    OpKind.VESTING, op.fundIndex(), op.participantIndex(), false, amount, -1, -1));
          }
        }
        case REDEMPTION -> {
          HoldingsKey key = new HoldingsKey(op.fundIndex(), op.participantIndex(), op.vested());
          long amount = Math.min(holdings.getOrDefault(key, 0L), op.rawAmount());
          if (amount > 0) {
            holdings.merge(key, -amount, Long::sum);
            unitsInIssue.merge(op.fundIndex(), -amount, Long::sum);
            resolved.add(
                new ResolvedOp(
                    OpKind.REDEMPTION,
                    op.fundIndex(),
                    op.participantIndex(),
                    op.vested(),
                    amount,
                    -1,
                    -1));
          }
        }
        case CASH_MOVE -> {
          int from = op.cashFromIndex();
          int to = op.cashToIndex() == from ? (from + 1) % CASH_ACCOUNT_COUNT : op.cashToIndex();
          cash.merge(from, -op.rawAmount(), Long::sum);
          cash.merge(to, op.rawAmount(), Long::sum);
          resolved.add(new ResolvedOp(OpKind.CASH_MOVE, -1, -1, false, op.rawAmount(), from, to));
        }
      }
    }
    return new ShadowModel(resolved, holdings, unitsInIssue, cash);
  }

  private List<CreateTransferCommand> toCommands(List<ResolvedOp> resolved, AccountSet accounts) {
    List<CreateTransferCommand> commands = new ArrayList<>(resolved.size());
    for (ResolvedOp op : resolved) {
      switch (op.kind()) {
        case CONTRIBUTION ->
            commands.add(
                CreateTransferCommand.Unit.single(
                    freshTransferId(),
                    accounts.unitsInIssue.get(op.fundIndex()),
                    accounts.holdingsAccount(op.fundIndex(), op.participantIndex(), op.vested()),
                    accounts.fundLedgerIds.get(op.fundIndex()),
                    LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                    Units.of(op.amount())));
        case VESTING ->
            commands.add(
                CreateTransferCommand.Unit.single(
                    freshTransferId(),
                    accounts.holdingsAccount(op.fundIndex(), op.participantIndex(), false),
                    accounts.holdingsAccount(op.fundIndex(), op.participantIndex(), true),
                    accounts.fundLedgerIds.get(op.fundIndex()),
                    LedgerCodes.TransferCode.VESTING,
                    Units.of(op.amount())));
        case REDEMPTION ->
            commands.add(
                CreateTransferCommand.Unit.single(
                    freshTransferId(),
                    accounts.holdingsAccount(op.fundIndex(), op.participantIndex(), op.vested()),
                    accounts.unitsInIssue.get(op.fundIndex()),
                    accounts.fundLedgerIds.get(op.fundIndex()),
                    LedgerCodes.TransferCode.REDEMPTION,
                    Units.of(op.amount())));
        case CASH_MOVE ->
            commands.add(
                CreateTransferCommand.Cash.single(
                    freshTransferId(),
                    accounts.cashAccounts.get(op.cashFrom()),
                    accounts.cashAccounts.get(op.cashTo()),
                    LedgerCodes.TransferCode.CASH_RECEIPT,
                    Money.of(op.amount(), CurrencyCode.MXN)));
      }
    }
    return commands;
  }

  // -----------------------------------------------------------------------------------------
  // Fresh accounts per try - TigerBeetle is append-only, see TigerBeetleFixture's own warning.
  // -----------------------------------------------------------------------------------------

  private record AccountSet(
      List<Long> fundLedgerIds,
      List<AccountId> unitsInIssue,
      Map<HoldingsKey, AccountId> holdings,
      List<AccountId> cashAccounts) {

    AccountId holdingsAccount(int fundIndex, int participantIndex, boolean vested) {
      return holdings.get(new HoldingsKey(fundIndex, participantIndex, vested));
    }

    List<AccountId> allHoldingsAccountIds() {
      return List.copyOf(holdings.values());
    }

    List<AccountId> allAccountIds() {
      List<AccountId> all = new ArrayList<>(unitsInIssue);
      all.addAll(holdings.values());
      all.addAll(cashAccounts);
      return all;
    }
  }

  private AccountSet freshAccountSet() {
    List<Long> fundLedgerIds = new ArrayList<>(FUND_COUNT);
    List<AccountId> unitsInIssue = new ArrayList<>(FUND_COUNT);
    Map<HoldingsKey, AccountId> holdings = new HashMap<>();

    for (int f = 0; f < FUND_COUNT; f++) {
      long fundLedgerId = FUND_LEDGER_SEQ.incrementAndGet();
      fundLedgerIds.add(fundLedgerId);

      AccountId issueAccount = freshAccountId();
      port.createAccount(
          new CreateAccountCommand(
              issueAccount, fundLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
      unitsInIssue.add(issueAccount);

      for (int p = 0; p < PARTICIPANT_COUNT; p++) {
        AccountId vestedAccount = freshAccountId();
        port.createAccount(
            new CreateAccountCommand(
                vestedAccount, fundLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));
        holdings.put(new HoldingsKey(f, p, true), vestedAccount);

        AccountId unvestedAccount = freshAccountId();
        port.createAccount(
            new CreateAccountCommand(
                unvestedAccount,
                fundLedgerId,
                LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED));
        holdings.put(new HoldingsKey(f, p, false), unvestedAccount);
      }
    }

    List<AccountId> cashAccounts = new ArrayList<>(CASH_ACCOUNT_COUNT);
    for (int c = 0; c < CASH_ACCOUNT_COUNT; c++) {
      AccountId cashAccount = freshAccountId();
      port.createAccount(
          new CreateAccountCommand(cashAccount, LedgerCodes.CASH_LEDGER_ID, CASH_ACCOUNT_CODES[c]));
      cashAccounts.add(cashAccount);
    }

    return new AccountSet(fundLedgerIds, unitsInIssue, holdings, cashAccounts);
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
