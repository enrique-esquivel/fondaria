package dev.fondaria.balanceapi.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link BalanceService} — the balance is {@code units × NAV}, and only that.
 *
 * <p>Every assertion here is against {@code Money.value}, the platform's one place that
 * multiplication happens, rather than against a number typed into the test. A test that hardcoded
 * the expected cents would pass just as happily if the service and the test both rounded wrongly in
 * the same direction.
 */
class BalanceServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

  private static final OrganizationId ORGANIZATION =
      new OrganizationId(UUID.fromString("2f1c8a5e-0000-4000-8000-000000000001"));
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void valuesVestedAndUnvestedUnitsSeparatelyAtTheFundsLivePrice() {
    FundId fund = FundId.of(UUID.randomUUID());
    long navScaled = 147_38300000L; // 147.383 pesos per unit, scale 1e-8
    long vested = 321_48825610L;
    long unvested = 50_00000000L;

    ParticipantBalance balance =
        serviceFor(
                new FakeNavCache().with(fund, navScaled, 48213),
                ledgerHolding(fund, 1001L, vested, unvested))
            .balanceOf(PARTICIPANT);

    Nav nav = Nav.of(navScaled, CurrencyCode.MXN);
    Money expectedVested = Money.value(Units.of(vested), nav);
    Money expectedUnvested = Money.value(Units.of(unvested), nav);

    assertThat(balance.positions()).hasSize(1);
    FundPosition position = balance.positions().get(0);
    assertThat(position.fundId()).isEqualTo(fund);
    assertThat(position.vestedUnits()).isEqualTo(Units.of(vested));
    assertThat(position.unvestedUnits()).isEqualTo(Units.of(unvested));
    assertThat(position.vestedValue()).isEqualTo(expectedVested);
    assertThat(position.unvestedValue()).isEqualTo(expectedUnvested);
    assertThat(position.value()).isEqualTo(expectedVested.plus(expectedUnvested));

    // The headline figure is exactly the sum of the parts a reader can see.
    assertThat(balance.investedValue()).isEqualTo(expectedVested.plus(expectedUnvested));
    assertThat(balance.asOf()).isEqualTo(NOW);
    assertThat(balance.participantId()).isEqualTo(PARTICIPANT);
  }

  /**
   * Unvested units are issued and priced; vesting governs entitlement, not valuation (ADR-0004).
   */
  @Test
  void unvestedUnitsAreIncludedInTheInvestedValue() {
    FundId fund = FundId.of(UUID.randomUUID());
    ParticipantBalance withUnvested =
        serviceFor(
                new FakeNavCache().with(fund, 100_00000000L, 1),
                ledgerHolding(fund, 1001L, 10_00000000L, 40_00000000L))
            .balanceOf(PARTICIPANT);

    // 50 units at 100.00 => 5,000.00 pesos => 500,000 cents, regardless of the vested split.
    assertThat(withUnvested.investedValue()).isEqualTo(Money.of(500_000L, CurrencyCode.MXN));
  }

  @Test
  void sumsAcrossEveryFundHeld() {
    FundId first = FundId.of(UUID.randomUUID());
    FundId second = FundId.of(UUID.randomUUID());

    FakeLedgerPort ledger = new FakeLedgerPort();
    hold(ledger, first, 1001L, 100_00000000L, 0L);
    hold(ledger, second, 1002L, 25_00000000L, 0L);

    ParticipantBalance balance =
        serviceFor(
                new FakeNavCache().with(first, 10_00000000L, 1).with(second, 200_00000000L, 2),
                ledger)
            .balanceOf(PARTICIPANT);

    assertThat(balance.positions()).hasSize(2);
    // 100 × 10.00 = 1,000.00 and 25 × 200.00 = 5,000.00 => 600,000 cents.
    assertThat(balance.investedValue()).isEqualTo(Money.of(600_000L, CurrencyCode.MXN));
  }

  /**
   * A fund the participant has never been allotted into has no holding account at all, so it is
   * absent from the positions rather than reported as a zero holding.
   */
  @Test
  void omitsAFundThePartipantHoldsNothingIn() {
    FundId held = FundId.of(UUID.randomUUID());
    FundId neverHeld = FundId.of(UUID.randomUUID());

    ParticipantBalance balance =
        serviceFor(
                new FakeNavCache().with(held, 10_00000000L, 1).with(neverHeld, 99_00000000L, 2),
                ledgerHolding(held, 1001L, 1_00000000L, 0L))
            .balanceOf(PARTICIPANT);

    assertThat(balance.positions())
        .singleElement()
        .extracting(FundPosition::fundId)
        .isEqualTo(held);
  }

  /**
   * A participant who exists and holds nothing is a real answer — zero and an empty list, never a
   * {@code 404}, which says something different.
   */
  @Test
  void aParticipantHoldingNothingIsZeroRatherThanNotFound() {
    FundId fund = FundId.of(UUID.randomUUID());

    ParticipantBalance balance =
        serviceFor(new FakeNavCache().with(fund, 10_00000000L, 1), new FakeLedgerPort())
            .balanceOf(PARTICIPANT);

    assertThat(balance.positions()).isEmpty();
    assertThat(balance.investedValue()).isEqualTo(Money.of(0, CurrencyCode.MXN));
    assertThat(balance.stale()).isFalse();
  }

  @Test
  void anUnknownParticipantIsNotFound() {
    BalanceService service =
        new BalanceService(
            participantId -> Optional.empty(),
            new FakeLedgerPort(),
            new FakeNavCache(),
            accountIds,
            clock);

    assertThatThrownBy(() -> service.balanceOf(PARTICIPANT))
        .isInstanceOf(ParticipantNotFoundException.class);
  }

  /** A stale price is served, marked — never withheld and never silently. */
  @Test
  void marksTheResponseStaleWhenAnyFundsPriceIsStale() {
    FundId fresh = FundId.of(UUID.randomUUID());
    FundId stale = FundId.of(UUID.randomUUID());

    FakeLedgerPort ledger = new FakeLedgerPort();
    hold(ledger, fresh, 1001L, 1_00000000L, 0L);
    hold(ledger, stale, 1002L, 1_00000000L, 0L);

    ParticipantBalance balance =
        serviceFor(
                new FakeNavCache()
                    .with(fresh, 10_00000000L, 1, false)
                    .with(stale, 20_00000000L, 2, true),
                ledger)
            .balanceOf(PARTICIPANT);

    assertThat(balance.stale()).isTrue();
    assertThat(balance.positions())
        .filteredOn(position -> position.fundId().equals(stale))
        .singleElement()
        .satisfies(position -> assertThat(position.nav().stale()).isTrue());
    assertThat(balance.positions())
        .filteredOn(position -> position.fundId().equals(fresh))
        .singleElement()
        .satisfies(position -> assertThat(position.nav().stale()).isFalse());
    // Still valued: a stale price prices the holding, it does not suppress it.
    assertThat(balance.investedValue()).isEqualTo(Money.of(3_000L, CurrencyCode.MXN));
  }

  /**
   * Every priced figure must name the {@code reference.fund_nav} row behind it
   * (docs/ENGINEERING-RULES.md §4).
   */
  @Test
  void carriesTheNavVersionThatProducedEachFundsFigures() {
    FundId first = FundId.of(UUID.randomUUID());
    FundId second = FundId.of(UUID.randomUUID());

    FakeLedgerPort ledger = new FakeLedgerPort();
    hold(ledger, first, 1001L, 1_00000000L, 0L);
    hold(ledger, second, 1002L, 1_00000000L, 0L);

    ParticipantBalance balance =
        serviceFor(
                new FakeNavCache().with(first, 10_00000000L, 7001).with(second, 20_00000000L, 7002),
                ledger)
            .balanceOf(PARTICIPANT);

    assertThat(balance.positions())
        .extracting(position -> position.nav().navVersionId())
        .containsExactlyInAnyOrder(7001L, 7002L);
  }

  /**
   * A holding account is on a unit ledger by construction. If one ever resolved to a cash-ledger
   * account the id derivation would have collided with a cash account, and every figure downstream
   * would be wrong — so it fails loudly rather than being skipped or read as pesos.
   */
  @Test
  void aHoldingAccountThatResolvesToCashFailsLoudly() {
    FundId fund = FundId.of(UUID.randomUUID());
    String scope = ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fund;
    FakeLedgerPort ledger =
        new FakeLedgerPort()
            .withCashAccountAt(
                vestedAccount(scope), LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED);

    BalanceService service = serviceFor(new FakeNavCache().with(fund, 10_00000000L, 1), ledger);

    assertThatThrownBy(() -> service.balanceOf(PARTICIPANT))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * The bug an adversarial review found: an empty NAV cache produced no candidate funds, therefore
   * no positions, therefore a confident {@code 200} reporting zero pesos with {@code stale: false}.
   * No prices means this instance cannot answer — it does not mean the answer is zero.
   */
  @Test
  void anEmptyPriceSetFailsRatherThanReportingZero() {
    BalanceService service = serviceFor(new FakeNavCache(), new FakeLedgerPort());

    assertThatThrownBy(() -> service.balanceOf(PARTICIPANT))
        .isInstanceOf(PricesUnavailableException.class);
  }

  /**
   * The distinction the exception above must not blur: a participant who holds nothing in funds
   * that <i>are</i> priced is a real zero, and is still served as one.
   */
  @Test
  void aRealZeroIsStillServedWhenPricesExist() {
    FundId fund = FundId.of(UUID.randomUUID());

    ParticipantBalance balance =
        serviceFor(new FakeNavCache().with(fund, 10_00000000L, 1), new FakeLedgerPort())
            .balanceOf(PARTICIPANT);

    assertThat(balance.investedValue()).isEqualTo(Money.of(0, CurrencyCode.MXN));
  }

  @Test
  void anUnreachableLedgerFailsRatherThanReportingZero() {
    FundId fund = FundId.of(UUID.randomUUID());
    BalanceService service =
        serviceFor(
            new FakeNavCache().with(fund, 10_00000000L, 1), new FakeLedgerPort().unavailable());

    assertThatThrownBy(() -> service.balanceOf(PARTICIPANT))
        .isInstanceOf(LedgerUnavailableException.class);
  }

  /**
   * The acceptance criterion "read cost does not grow with participant count", proved structurally:
   * one batched ledger call, of exactly two account ids per fund, and nothing else. Participant
   * count cannot appear in that figure because nothing in the path iterates participants.
   */
  @Test
  void readsEveryFundInOneBatchOfTwoAccountsPerFund() {
    FakeNavCache prices = new FakeNavCache();
    FakeLedgerPort ledger = new FakeLedgerPort();
    for (int i = 0; i < 7; i++) {
      FundId fund = FundId.of(UUID.randomUUID());
      prices.with(fund, 10_00000000L, i);
      hold(ledger, fund, 1001L + i, 1_00000000L, 0L);
    }

    serviceFor(prices, ledger).balanceOf(PARTICIPANT);

    assertThat(ledger.lookupBatches()).hasSize(1);
    assertThat(ledger.lookupBatches().get(0)).hasSize(14);
  }

  /**
   * "Balance matches units × NAV exactly after any sequence of contributions", over a volatile
   * series that genuinely falls — a suite that only ever raises the price passes on a broken
   * implementation (docs/ENGINEERING-RULES.md §4).
   */
  @Test
  void matchesUnitsTimesNavOverAVolatileFallingSeries() {
    Random random = new Random(20260901L);
    FundId fund = FundId.of(UUID.randomUUID());
    long navScaled = 100_00000000L;
    long vested = 0;
    long unvested = 0;
    boolean everFell = false;

    for (int round = 0; round < 200; round++) {
      // A contribution: more units at whatever the price is. Never moves the NAV.
      vested += random.nextLong(1, 5_000_00000000L);
      if (random.nextInt(3) == 0) {
        unvested += random.nextLong(1, 1_000_00000000L);
      }
      // A strike: the price moves, sometimes down, occasionally hard.
      long move = random.nextLong(-30_00000000L, 30_00000000L);
      long moved = navScaled + move;
      navScaled = Math.max(1, moved);
      everFell |= move < 0;

      ParticipantBalance balance =
          serviceFor(
                  new FakeNavCache().with(fund, navScaled, round),
                  ledgerHolding(fund, 1001L, vested, unvested))
              .balanceOf(PARTICIPANT);

      Nav nav = Nav.of(navScaled, CurrencyCode.MXN);
      Money expected =
          Money.value(Units.of(vested), nav).plus(Money.value(Units.of(unvested), nav));
      assertThat(balance.investedValue())
          .as("round %d at nav %d", round, navScaled)
          .isEqualTo(expected);
    }
    assertThat(everFell).as("the series must include falling prices").isTrue();
  }

  // -------------------------------------------------------------------------------------------

  private BalanceService serviceFor(FakeNavCache prices, FakeLedgerPort ledger) {
    ParticipantDirectory directory =
        participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF));
    return new BalanceService(directory, ledger, prices, accountIds, clock);
  }

  private FakeLedgerPort ledgerHolding(FundId fund, long fundLedgerId, long vested, long unvested) {
    FakeLedgerPort ledger = new FakeLedgerPort();
    hold(ledger, fund, fundLedgerId, vested, unvested);
    return ledger;
  }

  /**
   * Opens the participant's two holding accounts under the same scope {@code AllotmentService}
   * uses.
   */
  private void hold(
      FakeLedgerPort ledger, FundId fund, long fundLedgerId, long vested, long unvested) {
    String scope = ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fund;
    if (vested > 0) {
      ledger.withUnitAccount(
          vestedAccount(scope),
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
          fundLedgerId,
          vested);
    }
    if (unvested > 0) {
      ledger.withUnitAccount(
          unvestedAccount(scope),
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
          fundLedgerId,
          unvested);
    }
  }

  private AccountId vestedAccount(String scope) {
    return accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope);
  }

  private AccountId unvestedAccount(String scope) {
    return accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope);
  }
}
