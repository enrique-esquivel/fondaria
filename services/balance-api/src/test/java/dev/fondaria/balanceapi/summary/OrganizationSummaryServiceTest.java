package dev.fondaria.balanceapi.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.balance.PricesUnavailableException;
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
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.ledger.tigerbeetle.LedgerUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrganizationSummaryService} — an employer's aggregate, and the one property that makes it
 * worth anything: it equals the sum of the balances its own participants are served.
 *
 * <p><b>The tie-out tests do not compare against numbers typed into this file.</b> They run {@code
 * BalanceService} over the same fake ledger and the same fake price set, participant by
 * participant, and add the results up. A hardcoded expectation would pass just as happily if the
 * two services rounded wrongly in the same direction, which is the failure this criterion exists to
 * catch.
 */
class OrganizationSummaryServiceTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");

  private static final OrganizationId ACME =
      new OrganizationId(UUID.fromString("2f1c8a5e-0000-4000-8000-0000000000a1"));
  private static final OrganizationId RIVAL =
      new OrganizationId(UUID.fromString("2f1c8a5e-0000-4000-8000-0000000000b2"));

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  // ---------------------------------------------------------------------------------------------
  // "Totals tie exactly to the sum of participant balances."
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the organization total is exactly the sum of its participants' own balances")
  void totalsTieToTheSumOfParticipantBalances() {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeNavCache prices = new FakeNavCache().with(fund, 147_38300000L, 48213);
    World world = new World(prices);
    // Deliberately ragged holdings, including two lots whose products land exactly on a half
    // centavo, which is where per-lot and aggregate rounding part company.
    world.hold(ACME, "EMP-0001", fund, 1001L, 321_48825610L, 50_00000000L);
    world.hold(ACME, "EMP-0002", fund, 1001L, 500_000L, 500_000L);
    world.hold(ACME, "EMP-0003", fund, 1001L, 7_77777777L, 0L);
    world.hold(ACME, "EMP-0004", fund, 1001L, 0L, 12_34567890L);
    world.enrol(ACME, "EMP-0005"); // enrolled, never allotted: no accounts at all

    OrganizationSummary summary = world.service().summaryOf(ACME);

    assertThat(summary.totalValue()).isEqualTo(world.sumOfParticipantBalances(ACME));
    assertThat(summary.totalParticipants()).isEqualTo(5);
    assertThat(summary.funds())
        .singleElement()
        .satisfies(
            total -> {
              assertThat(total.fundId()).isEqualTo(fund);
              assertThat(total.totalUnits())
                  .isEqualTo(
                      Units.of(
                          321_48825610L
                              + 50_00000000L
                              + 500_000L
                              + 500_000L
                              + 7_77777777L
                              + 12_34567890L));
              assertThat(total.totalValue()).isEqualTo(summary.totalValue());
            });
    assertThat(summary.asOf()).isEqualTo(NOW);
    assertThat(summary.organizationId()).isEqualTo(ACME);
  }

  @Test
  @DisplayName("the tie holds across many funds and a volatile NAV series, losses included")
  void theTieHoldsAcrossFundsAndAVolatileNavSeries() {
    List<FundId> funds =
        List.of(
            FundId.of(UUID.randomUUID()),
            FundId.of(UUID.randomUUID()),
            FundId.of(UUID.randomUUID()));
    Random random = new Random(20260915L);
    long[] navs = {10_00000000L, 12_35000000L, 8_10000000L, 8_09999999L, 15_00000001L, 3_33333333L};

    boolean everFell = false;
    long previous = Long.MAX_VALUE;
    for (long navScaled : navs) {
      FakeNavCache prices = new FakeNavCache();
      for (int f = 0; f < funds.size(); f++) {
        // Each fund carries a different price, so a service that priced everything off the first
        // one would not survive the tie-out.
        prices.with(funds.get(f), navScaled + f * 7_777L, 100 + f);
      }
      World world = new World(prices);
      for (int p = 0; p < 40; p++) {
        String internalRef = String.format("EMP-%04d", p);
        world.enrol(ACME, internalRef);
        for (int f = 0; f < funds.size(); f++) {
          world.hold(
              ACME,
              internalRef,
              funds.get(f),
              1001L + f,
              Math.abs(random.nextLong() % 500_00000000L),
              Math.abs(random.nextLong() % 100_00000000L));
        }
      }

      OrganizationSummary summary = world.service().summaryOf(ACME);
      assertThat(summary.totalValue())
          .as("nav %d", navScaled)
          .isEqualTo(world.sumOfParticipantBalances(ACME));

      everFell |= navScaled < previous;
      previous = navScaled;
    }
    assertThat(everFell).as("the series must include falling prices").isTrue();
  }

  /**
   * The divergence {@code ADR-0015} accepts, pinned with both numbers so a refactor to the other
   * form fails rather than passing on values that happen to agree.
   */
  @Test
  @DisplayName("the fund total is the sum of rounded lots, not the rounded product of the totals")
  void theFundTotalIsTheSumOfRoundedLotsNotTheRoundedAggregate() {
    FundId fund = FundId.of(UUID.randomUUID());
    long navScaled = 1_00000000L; // exactly one peso per unit
    FakeNavCache prices = new FakeNavCache().with(fund, navScaled, 7);
    World world = new World(prices);
    // 0.005 units at 1.00 is half a centavo. Four such lots round to four centavos one at a
    // time, and to two centavos if the units are added up first.
    world.hold(ACME, "EMP-0001", fund, 1001L, 500_000L, 500_000L);
    world.hold(ACME, "EMP-0002", fund, 1001L, 500_000L, 500_000L);

    OrganizationSummary summary = world.service().summaryOf(ACME);
    OrganizationFundTotal total = summary.funds().get(0);

    assertThat(total.totalUnits()).isEqualTo(Units.of(2_000_000L));
    assertThat(total.totalValue()).isEqualTo(Money.of(4, CurrencyCode.MXN));
    assertThat(Money.value(total.totalUnits(), Nav.of(navScaled, CurrencyCode.MXN)))
        .as("the rounded aggregate product is a different, and deliberately unused, number")
        .isEqualTo(Money.of(2, CurrencyCode.MXN));
    assertThat(total.totalValue()).isEqualTo(world.sumOfParticipantBalances(ACME));
  }

  // ---------------------------------------------------------------------------------------------
  // "An employer cannot query another organization."
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("one organization's summary carries nothing belonging to another")
  void oneOrganizationsSummaryCarriesNothingOfAnothers() {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeNavCache prices = new FakeNavCache().with(fund, 10_00000000L, 11);
    World world = new World(prices);
    world.hold(ACME, "EMP-0001", fund, 1001L, 100_00000000L, 0L);
    world.hold(RIVAL, "EMP-0001", fund, 1001L, 900_00000000L, 0L);
    world.hold(RIVAL, "EMP-0002", fund, 1001L, 700_00000000L, 0L);

    OrganizationSummary acme = world.service().summaryOf(ACME);
    OrganizationSummary rival = world.service().summaryOf(RIVAL);

    assertThat(acme.totalParticipants()).isEqualTo(1);
    assertThat(acme.totalValue()).isEqualTo(world.sumOfParticipantBalances(ACME));
    assertThat(rival.totalParticipants()).isEqualTo(2);
    assertThat(rival.totalValue()).isEqualTo(world.sumOfParticipantBalances(RIVAL));
    assertThat(acme.totalValue().cents()).isLessThan(rival.totalValue().cents());
  }

  /**
   * The stronger half of the isolation claim: the scope comes from the path, not from the roster.
   *
   * <p>Here the roster is made to leak — it reports another employer's participant as one of this
   * employer's — and every <b>money</b> figure is still exactly this employer's. Nothing filters
   * the leaked row out; the account ids are derived under the requested organization, and the
   * leaked participant holds no account under that scope.
   *
   * <p><b>The isolation is partial, and the assertions below say so.</b> {@code totalParticipants}
   * <i>is</i> corrupted by the leak — it counts the roster as given — because a headcount has
   * nothing to derive against. What is claimed is narrow and true: no money crosses. Note also that
   * {@code OrganizationRoster} hands back bare internal references and no organization id, so there
   * is structurally little for a roster to leak the money with; this test pins that the service
   * never resolves a reference back to whichever employer it really belongs to.
   */
  @Test
  @DisplayName("a roster leaking a foreign participant still cannot leak that employer's units")
  void aLeakyRosterCannotLeakAnotherEmployersUnits() {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeNavCache prices = new FakeNavCache().with(fund, 10_00000000L, 11);
    World world = new World(prices);
    world.hold(ACME, "EMP-0001", fund, 1001L, 100_00000000L, 0L);
    world.hold(RIVAL, "RIVAL-0001", fund, 1001L, 900_00000000L, 0L);
    Money honest = world.service().summaryOf(ACME).totalValue();

    // The roster now hands Acme's summary a participant who belongs to the rival employer.
    world.roster().with(ACME, "RIVAL-0001");
    OrganizationSummary leaked = world.service().summaryOf(ACME);

    assertThat(leaked.totalParticipants()).as("the roster is counted as given").isEqualTo(2);
    assertThat(leaked.totalValue())
        .as("but not one centavo of the rival's money enters the total")
        .isEqualTo(honest);
  }

  @Test
  @DisplayName(
      "the contributions figure is asked for under this organization's own tenant and account")
  void theContributionsFigureIsScopedToThisOrganization() {
    FundId fund = FundId.of(UUID.randomUUID());
    World world = new World(new FakeNavCache().with(fund, 10_00000000L, 11));
    world.hold(ACME, "EMP-0001", fund, 1001L, 1_00000000L, 0L);
    world.contributions().returning(16_200_00L);

    OrganizationSummary summary = world.service().summaryOf(ACME);

    assertThat(summary.contributionsThisPeriod()).isEqualTo(Money.of(16_200_00L, CurrencyCode.MXN));
    assertThat(world.contributions().asks())
        .singleElement()
        .satisfies(
            ask -> {
              assertThat(ask.tenantId()).isEqualTo(tenantRefs.derive(ACME).asUnsignedDecimal());
              assertThat(ask.employerCollectionsAccountId())
                  .isEqualTo(
                      accountIds
                          .derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, ACME.scope())
                          .asUnsignedDecimal());
            });
  }

  // ---------------------------------------------------------------------------------------------
  // The period.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the period is the calendar month of the reading clock, in the platform's zone")
  void thePeriodIsTheCalendarMonthInThePlatformZone() {
    FundId fund = FundId.of(UUID.randomUUID());
    World world = new World(new FakeNavCache().with(fund, 10_00000000L, 11));
    world.enrol(ACME, "EMP-0001");

    OrganizationSummary summary = world.service().summaryOf(ACME);

    assertThat(summary.period())
        .isEqualTo(new StatementPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
  }

  /**
   * The reason the zone is read at all. At 03:00 UTC on 1 September it is still 31 August in Mexico
   * City, and an employer looking at their summary then must see August's contributions rather than
   * an empty September.
   */
  @Test
  @DisplayName("a UTC instant just past midnight still belongs to the previous month locally")
  void theMonthBoundaryIsTakenLocallyNotInUtc() {
    FundId fund = FundId.of(UUID.randomUUID());
    World world =
        new World(
            new FakeNavCache().with(fund, 10_00000000L, 11),
            Clock.fixed(Instant.parse("2026-09-01T03:00:00Z"), ZoneOffset.UTC));
    world.enrol(ACME, "EMP-0001");

    assertThat(world.service().summaryOf(ACME).period())
        .isEqualTo(new StatementPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
  }

  // ---------------------------------------------------------------------------------------------
  // Batching: O(participants) work, but never O(participants) round trips.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a roster larger than one ledger batch is walked in slices, none over the limit")
  void aLargeRosterIsWalkedInBoundedSlices() {
    FundId fund = FundId.of(UUID.randomUUID());
    World world = new World(new FakeNavCache().with(fund, 10_00000000L, 11));
    int participants = 4_100; // one fund => 4,000 participants per slice
    for (int i = 0; i < participants; i++) {
      world.hold(ACME, String.format("EMP-%05d", i), fund, 1001L, 1_00000000L, 0L);
    }

    OrganizationSummary summary = world.service().summaryOf(ACME);

    // Read before the tie-out below, which walks the same ledger participant by participant and
    // would otherwise add 4,100 batches of its own to what is being counted here.
    List<List<AccountId>> batches = world.ledger().lookupBatches();
    assertThat(batches).as("one batched ledger call per slice, not one per participant").hasSize(2);
    assertThat(batches)
        .allSatisfy(
            batch ->
                assertThat(batch.size())
                    .isLessThanOrEqualTo(OrganizationSummaryService.MAX_ACCOUNT_IDS_PER_LOOKUP));

    assertThat(summary.totalParticipants()).isEqualTo(participants);
    assertThat(summary.totalValue()).isEqualTo(world.sumOfParticipantBalances(ACME));
  }

  @Test
  @DisplayName("the slice shrinks as the fund registry grows, so a batch stays within the limit")
  void theSliceShrinksAsTheFundRegistryGrows() {
    FakeNavCache prices = new FakeNavCache();
    for (int f = 0; f < 3; f++) {
      prices.with(FundId.of(UUID.randomUUID()), 10_00000000L, 11 + f);
    }
    World world = new World(prices);
    // 8,000 / (3 funds x 2 lots) = 1,333 participants per slice, so 1,400 needs two.
    for (int p = 0; p < 1_400; p++) {
      world.enrol(ACME, String.format("EMP-%05d", p));
    }

    world.service().summaryOf(ACME);

    assertThat(world.ledger().lookupBatches())
        .hasSize(2)
        .allSatisfy(
            batch ->
                assertThat(batch.size())
                    .isLessThanOrEqualTo(OrganizationSummaryService.MAX_ACCOUNT_IDS_PER_LOOKUP));
    assertThat(world.ledger().lookupBatches().get(0)).hasSize(1_333 * 3 * 2);
  }

  /**
   * Past roughly 4,000 funds a single participant's own account ids exceed one batch, and no page
   * size can fix that — a page of one is already too big. {@code LedgerPort.lookupAccounts} does
   * not split a batch of its own accord, so the cap has to be enforced by the caller or the request
   * fails outright.
   */
  @Test
  @DisplayName("one participant's own ids are split when the fund registry alone overflows a batch")
  void oneParticipantsOwnIdsAreSplitWhenTheRegistryOverflowsABatch() {
    FakeNavCache prices = new FakeNavCache();
    int funds = OrganizationSummaryService.MAX_ACCOUNT_IDS_PER_LOOKUP / 2 + 1;
    for (int f = 0; f < funds; f++) {
      prices.with(FundId.of(UUID.randomUUID()), 10_00000000L, 1 + f);
    }
    World world = new World(prices);
    world.enrol(ACME, "EMP-0001");

    world.service().summaryOf(ACME);

    assertThat(world.ledger().lookupBatches())
        .as(
            "a slice can shrink no further than one participant, so its %d ids are split",
            funds * 2)
        .hasSize(2)
        .allSatisfy(
            batch ->
                assertThat(batch.size())
                    .isLessThanOrEqualTo(OrganizationSummaryService.MAX_ACCOUNT_IDS_PER_LOOKUP));
  }

  // ---------------------------------------------------------------------------------------------
  // What is not an answer.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an unknown organization is a 404, never a zero-filled summary")
  void anUnknownOrganizationIsNeverAZeroSummary() {
    World world = new World(new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1));
    world.enrol(ACME, "EMP-0001");

    assertThatThrownBy(() -> world.service().summaryOf(RIVAL))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  @DisplayName("a registered organization whose people hold nothing is an answer, not a 404")
  void anEmptyOrganizationIsAnAnswer() {
    World world = new World(new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1));
    world.roster().withOrganization(ACME);

    OrganizationSummary summary = world.service().summaryOf(ACME);

    assertThat(summary.totalParticipants()).isZero();
    assertThat(summary.funds()).isEmpty();
    assertThat(summary.totalValue()).isEqualTo(Money.of(0, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("no prices at all is a refusal, never a zero total")
  void noPricesIsARefusal() {
    World world = new World(new FakeNavCache());
    world.enrol(ACME, "EMP-0001");

    assertThatThrownBy(() -> world.service().summaryOf(ACME))
        .isInstanceOf(PricesUnavailableException.class);
  }

  @Test
  @DisplayName("an unreachable ledger fails the request, never reports a partial total")
  void anUnreachableLedgerFailsTheRequest() {
    FundId fund = FundId.of(UUID.randomUUID());
    World world = new World(new FakeNavCache().with(fund, 10_00000000L, 11));
    world.hold(ACME, "EMP-0001", fund, 1001L, 100_00000000L, 0L);
    world.ledger().unavailable();

    assertThatThrownBy(() -> world.service().summaryOf(ACME))
        .isInstanceOf(LedgerUnavailableException.class);
  }

  @Test
  @DisplayName("a fund nobody in the organization holds is absent, not reported as zero")
  void anUnheldFundIsAbsent() {
    FundId held = FundId.of(UUID.randomUUID());
    FundId unheld = FundId.of(UUID.randomUUID());
    World world =
        new World(new FakeNavCache().with(held, 10_00000000L, 11).with(unheld, 20_00000000L, 12));
    world.hold(ACME, "EMP-0001", held, 1001L, 5_00000000L, 0L);

    assertThat(world.service().summaryOf(ACME).funds())
        .singleElement()
        .satisfies(total -> assertThat(total.fundId()).isEqualTo(held));
  }

  @Test
  @DisplayName("a stale price is flagged at the top as well as on the fund it priced")
  void aStalePriceIsFlaggedAtTheTop() {
    FundId fresh = FundId.of(UUID.randomUUID());
    FundId old = FundId.of(UUID.randomUUID());
    World world =
        new World(
            new FakeNavCache().with(fresh, 10_00000000L, 11).with(old, 20_00000000L, 12, true));
    world.hold(ACME, "EMP-0001", fresh, 1001L, 5_00000000L, 0L);
    world.hold(ACME, "EMP-0001", old, 1002L, 5_00000000L, 0L);

    OrganizationSummary summary = world.service().summaryOf(ACME);

    assertThat(summary.stale()).isTrue();
    assertThat(summary.funds())
        .filteredOn(total -> total.fundId().equals(old))
        .singleElement()
        .satisfies(total -> assertThat(total.nav().stale()).isTrue());
  }

  // ---------------------------------------------------------------------------------------------
  // A world: an organization, its participants, their ledger accounts, and both services over them.
  // ---------------------------------------------------------------------------------------------

  private final class World {

    private final FakeOrganizationRoster roster = new FakeOrganizationRoster();
    private final FakeContributionTotalRepository contributions =
        new FakeContributionTotalRepository();
    private final FakeLedgerPort ledger = new FakeLedgerPort();
    private final FakeNavCache prices;
    private final Clock readingClock;
    private final Map<OrganizationId, Map<String, ParticipantId>> enrolled = new LinkedHashMap<>();
    private final Map<ParticipantId, ParticipantScope> scopes = new HashMap<>();

    private World(FakeNavCache prices) {
      this(prices, clock);
    }

    private World(FakeNavCache prices, Clock readingClock) {
      this.prices = prices;
      this.readingClock = readingClock;
    }

    private FakeOrganizationRoster roster() {
      return roster;
    }

    private FakeContributionTotalRepository contributions() {
      return contributions;
    }

    private FakeLedgerPort ledger() {
      return ledger;
    }

    private OrganizationSummaryService service() {
      return new OrganizationSummaryService(
          roster, ledger, prices, contributions, accountIds, tenantRefs, readingClock, ZONE);
    }

    /** Puts a participant on an organization's roster, with no ledger accounts. */
    private ParticipantId enrol(OrganizationId organizationId, String internalRef) {
      roster.with(organizationId, internalRef);
      return enrolled
          .computeIfAbsent(organizationId, id -> new LinkedHashMap<>())
          .computeIfAbsent(
              internalRef,
              ref -> {
                ParticipantId participantId = ParticipantId.of(UUID.randomUUID());
                scopes.put(participantId, new ParticipantScope(organizationId, ref));
                return participantId;
              });
    }

    /** Enrols, then opens the two holding accounts an allotment would have opened. */
    private void hold(
        OrganizationId organizationId,
        String internalRef,
        FundId fund,
        long fundLedgerId,
        long vested,
        long unvested) {
      enrol(organizationId, internalRef);
      String scope = new ParticipantScope(organizationId, internalRef).forFund(fund);
      if (vested > 0) {
        ledger.withUnitAccount(
            accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
            fundLedgerId,
            vested);
      }
      if (unvested > 0) {
        ledger.withUnitAccount(
            accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope),
            LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
            fundLedgerId,
            unvested);
      }
    }

    /**
     * What every one of this organization's participants is served by the balance endpoint, added
     * up — the figure the summary must equal.
     *
     * <p>Runs against the same {@link FakeLedgerPort}, so it records batches of its own — a test
     * asserting over {@code lookupBatches()} must read them before calling this.
     */
    private Money sumOfParticipantBalances(OrganizationId organizationId) {
      ParticipantDirectory directory =
          participantId -> Optional.ofNullable(scopes.get(participantId));
      BalanceService balances =
          new BalanceService(directory, ledger, prices, accountIds, readingClock);
      Money total = Money.of(0, CurrencyCode.MXN);
      for (ParticipantId participantId : enrolled.getOrDefault(organizationId, Map.of()).values()) {
        total = total.plus(balances.balanceOf(participantId).investedValue());
      }
      return total;
    }
  }
}
