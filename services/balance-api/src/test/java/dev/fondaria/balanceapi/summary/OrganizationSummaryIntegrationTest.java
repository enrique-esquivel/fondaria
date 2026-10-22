package dev.fondaria.balanceapi.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferResult;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import dev.fondaria.valuation.InMemoryNavCache;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The organization summary against a real PostgreSQL and a real TigerBeetle (Task 6.5).
 *
 * <p><b>Every test creates its own organizations, fund, participants and tenant partition</b>, and
 * asserts only over what it created. Fixtures are reused across runs and TigerBeetle cannot be
 * reset at all (AGENTS.md §4), so nothing here counts over a whole table, ledger or topic, and the
 * ledger namespace is fresh per run — a fixed one would let a run pass on accounts a previous run
 * opened.
 *
 * <h2>One permanent fund, and prices that always move forward</h2>
 *
 * <p><b>The fund is fixed and registered idempotently</b>, for the reason {@code
 * AsOfBalanceIntegrationTest} records: {@code reference.fund_nav} is append-only and its rows carry
 * a foreign key to {@code reference.fund_identity}, so a fund this suite prices can never
 * afterwards be deregistered. A fresh fund per test would leave six permanent, priced funds behind
 * on every run — and this endpoint fans out over <i>every</i> priced fund, two derived account ids
 * per fund per participant, so that creep would land squarely on this suite's own scale test. One
 * permanent row, once, is the bounded version of the trade.
 *
 * <p><b>Each strike takes the day after the fund's newest price</b>, read from the table rather
 * than chosen. It has to move forward, not back: this endpoint prices from the NAV cache, which
 * primes from {@code findLatestLivePerFund} with no date bound, so a run whose strikes were older
 * than an earlier run's would be served the earlier run's price and assert against a number nobody
 * struck. Forward is also what keeps {@code fund_nav_fund_date_original_unique} satisfied without
 * coordination. A handful of rows accumulate per run and cannot be removed; that is the price of a
 * priced fund, and the table is tiny.
 *
 * <p><b>Other suites' funds may be priced too, and that is fine.</b> Every assertion about a fund
 * names this suite's own, and the organization-level totals are unaffected because a fresh employer
 * holds nothing in anybody else's fund.
 *
 * <p><b>The scale test's size is overridable</b> with {@code -Dsummary.participants=N} and {@code
 * -Dsummary.holders=N}, the same arrangement Task 6.4 uses for its ten-million-row test, so the PR
 * loop can run it small and a nightly can run it full size.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrganizationSummaryIntegrationTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  /** The acceptance criterion's own number. */
  private static final int PARTICIPANTS_AT_SCALE =
      Integer.getInteger("summary.participants", 10_000);

  /**
   * How many of those participants are given real ledger accounts.
   *
   * <p>Opening an account is one round trip to a real cluster, so opening 20,000 of them would put
   * minutes of setup into a suite that already runs on every commit. The figure the criterion is
   * about is unaffected: the service derives and sends {@code 2 × funds} ids for <i>every</i>
   * enrolled participant whether the account exists or not, so the roster read, the derivation and
   * the ledger request are all full size at 10,000. What is smaller than production is the reply —
   * fewer accounts come back. That is a real difference and it is why the assertion is a bound with
   * headroom rather than a stopwatch reading.
   */
  private static final int HOLDERS_AT_SCALE =
      Math.min(PARTICIPANTS_AT_SCALE, Integer.getInteger("summary.holders", 2_000));

  /** Permanent, for the reason in this class's javadoc. */
  private static final FundId SUMMARY_FUND =
      FundId.of(UUID.fromString("c5f0d1e2-6c4b-4f7a-9d31-0000000006e5"));

  private static final long SUMMARY_UNIT_LEDGER = 990_101L;

  /** The floor a first-ever run strikes from; every later strike is the day after the newest. */
  private static final LocalDate FIRST_VALUATION_DATE = LocalDate.of(2020, 1, 2);

  private static final long UNITS_PER_HOLDER = 1_00000000L;

  /** Unit transfers per batched {@code createTransfers}; TigerBeetle caps a batch at 8,189. */
  private static final int TRANSFER_BATCH = 4_000;

  /** Rows per statement when cleanup removes an organization's participants. */
  private static final int DELETE_CHUNK = 2_000;

  @Autowired private OrganizationSummaryService summaries;
  @Autowired private BalanceService balances;
  @Autowired private JdbcClient jdbc;
  @Autowired private LedgerPort ledger;
  @Autowired private InMemoryNavCache navCache;
  @Autowired private Clock clock;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final List<String> tenantsCreated = new ArrayList<>();
  private final List<OrganizationId> organizationsCreated = new ArrayList<>();

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);
    registry.add("fondaria.query.cursor-secret", () -> "a-summary-test-cursor-signing-key");
    registry.add("fondaria.nav-cache.refresh-interval", () -> "1h");
  }

  /**
   * Leaves the shared database as it found it.
   *
   * <p><b>The fund is not among what is removed</b>, and cannot be: it is priced, and a priced fund
   * is permanent. See this class's javadoc for why there is only ever one of it.
   *
   * <p>Projection rows are removed by dropping the tenant's partitions, not by {@code DELETE}:
   * {@code projection.transfer} carries a {@code BEFORE DELETE} trigger that raises for every role,
   * owner included (V21), which is the point of it.
   */
  @AfterEach
  void removeWhatThisTestCreated() {
    for (OrganizationId organizationId : organizationsCreated) {
      // In chunks: every statement through this JdbcClient runs under the service's query
      // timeout (5 s), and removing the 10,000-participant crowd in one statement can exceed it.
      int deleted;
      do {
        deleted =
            jdbc.sql(
                    """
                    DELETE FROM master.participant
                     WHERE ctid IN (SELECT ctid FROM master.participant
                                     WHERE organization_id = :o LIMIT :chunk)
                    """)
                .param("o", organizationId.value())
                .param("chunk", DELETE_CHUNK)
                .update();
      } while (deleted > 0);
      jdbc.sql("DELETE FROM master.organization WHERE organization_id = :o")
          .param("o", organizationId.value())
          .update();
    }
    organizationsCreated.clear();

    for (String tenantId : tenantsCreated) {
      String slug =
          jdbc.sql("SELECT left(md5(:t), 16)").param("t", tenantId).query(String.class).single();
      jdbc.sql("DROP TABLE IF EXISTS projection.transfer_t_" + slug + " CASCADE").update();
      jdbc.sql("DROP TABLE IF EXISTS projection.participant_units_t_" + slug + " CASCADE").update();
      jdbc.sql("DELETE FROM projection.partitioned_tenant WHERE tenant_id = :t")
          .param("t", tenantId)
          .update();
    }
    tenantsCreated.clear();
  }

  // -------------------------------------------------------------------------------------------
  // "Totals tie exactly to the sum of participant balances."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the summary total is exactly the sum of the balances the endpoint serves")
  void theTotalTiesToTheSumOfTheBalancesServed() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    // Ragged holdings, including a lot whose product lands on a half centavo.
    List<ParticipantId> people =
        List.of(
            acme.enrolHolding(fund, 321_48825610L, 50_00000000L),
            acme.enrolHolding(fund, 500_000L, 500_000L),
            acme.enrolHolding(fund, 7_77777777L, 0L),
            acme.enrolHolding(fund, 0L, 12_34567890L),
            acme.enrol());
    strike(fund, Nav.of(147_38300000L, CurrencyCode.MXN));
    navCache.refreshAll();

    OrganizationSummary summary = summaries.summaryOf(acme.organizationId);

    assertThat(summary.totalParticipants()).isEqualTo(people.size());
    assertThat(summary.totalValue()).isEqualTo(sumOfBalances(people));
    assertThat(summary.funds())
        .filteredOn(total -> total.fundId().equals(fund.fundId))
        .singleElement()
        .satisfies(
            total -> {
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
  }

  /**
   * A NAV that falls must move the employer's total down exactly as it moves each participant's,
   * and the tie must survive every step of a series that goes both ways. A suite that only ever
   * raised the price would pass on a broken implementation (AGENTS.md §4).
   */
  @Test
  @DisplayName("the tie survives a volatile NAV series, and a fall really lowers the total")
  void theTieSurvivesAVolatileNavSeries() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    List<ParticipantId> people =
        List.of(
            acme.enrolHolding(fund, 1_000_00000000L, 0L),
            acme.enrolHolding(fund, 250_00000000L, 33_33333333L),
            // A lot whose product lands on a half centavo at more than one price in the series.
            acme.enrolHolding(fund, 500_000L, 500_000L));

    long[] series = {20_00000000L, 8_50000000L, 12_34567891L, 8_49999999L, 1_00000000L};
    List<Money> totals = new ArrayList<>(series.length);
    for (long navScaled : series) {
      strike(fund, Nav.of(navScaled, CurrencyCode.MXN));
      navCache.refreshAll();
      OrganizationSummary summary = summaries.summaryOf(acme.organizationId);
      assertThat(summary.totalValue()).as("nav %d", navScaled).isEqualTo(sumOfBalances(people));
      totals.add(summary.totalValue());
    }

    assertThat(totals.get(1).cents())
        .as("a falling price lowers the employer's total, and is never clamped at the earlier one")
        .isLessThan(totals.get(0).cents());
    assertThat(totals.get(2).cents()).isGreaterThan(totals.get(1).cents());
    assertThat(totals.get(3).cents()).isLessThan(totals.get(2).cents());
    assertThat(totals.get(4).cents()).isLessThan(totals.get(3).cents());
  }

  // -------------------------------------------------------------------------------------------
  // "An employer cannot query another organization."
  // -------------------------------------------------------------------------------------------

  /**
   * What is provable today, stated precisely: a request naming one employer returns that employer's
   * numbers and no part of another's, with both employers' people holding units in the same fund on
   * the same ledger, and with contributions posted for both in the same period. Proving that the
   * <i>caller</i> is that employer is Phase 9's; this endpoint ships unauthenticated in v1 like
   * every other one here.
   */
  @Test
  @DisplayName("neither employer's summary carries any part of the other's")
  void neitherEmployersSummaryCarriesAnyPartOfTheOthers() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    Employer rival = registerEmployer();
    List<ParticipantId> acmePeople = List.of(acme.enrolHolding(fund, 100_00000000L, 0L));
    List<ParticipantId> rivalPeople =
        List.of(
            rival.enrolHolding(fund, 900_00000000L, 0L),
            rival.enrolHolding(fund, 700_00000000L, 0L));
    strike(fund, Nav.of(10_00000000L, CurrencyCode.MXN));
    navCache.refreshAll();

    LocalDate inPeriod = today().withDayOfMonth(1);
    acme.contribute(inPeriod, 500_00L);
    rival.contribute(inPeriod, 9_000_00L);

    OrganizationSummary acmeSummary = summaries.summaryOf(acme.organizationId);
    OrganizationSummary rivalSummary = summaries.summaryOf(rival.organizationId);

    assertThat(acmeSummary.totalParticipants()).isEqualTo(1);
    assertThat(acmeSummary.totalValue()).isEqualTo(sumOfBalances(acmePeople));
    assertThat(rivalSummary.totalParticipants()).isEqualTo(2);
    assertThat(rivalSummary.totalValue()).isEqualTo(sumOfBalances(rivalPeople));

    // And the flow figures are as separate as the positions.
    assertThat(acmeSummary.contributionsThisPeriod())
        .isEqualTo(Money.of(500_00L, CurrencyCode.MXN));
    assertThat(rivalSummary.contributionsThisPeriod())
        .isEqualTo(Money.of(9_000_00L, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("an unregistered organization is a 404, never a zero-filled summary")
  void anUnregisteredOrganizationIsNeverAZeroSummary() {
    Fund fund = registerFund();
    strike(fund, Nav.of(10_00000000L, CurrencyCode.MXN));
    navCache.refreshAll();

    assertThatThrownBy(() -> summaries.summaryOf(new OrganizationId(UUID.randomUUID())))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  // -------------------------------------------------------------------------------------------
  // Contributions this period, against real projected rows.
  // -------------------------------------------------------------------------------------------

  /**
   * The figure counts the {@code EMPLOYER_COLLECTIONS} leg of a {@code CONTRIBUTION_ALLOTMENT} and
   * nothing else. The fixture posts the whole cash chain — collections to suspense <i>and</i>
   * suspense to fund cash, the two legs {@code AllotmentService} really writes — so an
   * implementation matching on the transfer code alone reports exactly double and fails here.
   */
  @Test
  @DisplayName("contributions count each allotment once, inside the period, on the value date")
  void contributionsCountEachAllotmentOnceInsideThePeriod() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    acme.enrolHolding(fund, 100_00000000L, 0L);
    strike(fund, Nav.of(10_00000000L, CurrencyCode.MXN));
    navCache.refreshAll();

    LocalDate today = today();
    acme.contribute(today.withDayOfMonth(1), 1_000_00L);
    acme.contribute(today.withDayOfMonth(Math.min(15, today.lengthOfMonth())), 2_500_00L);
    // Last month, and next: both outside the window and neither may be counted.
    acme.contribute(today.withDayOfMonth(1).minusMonths(1), 7_777_77L);
    acme.contribute(today.withDayOfMonth(1).plusMonths(1), 8_888_88L);

    OrganizationSummary summary = summaries.summaryOf(acme.organizationId);

    assertThat(summary.contributionsThisPeriod()).isEqualTo(Money.of(3_500_00L, CurrencyCode.MXN));
    assertThat(summary.period().start()).isEqualTo(today.withDayOfMonth(1));
    assertThat(summary.period().end()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
  }

  @Test
  @DisplayName("an employer that contributed nothing this period reports zero, not a failure")
  void anEmployerThatContributedNothingReportsZero() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    acme.enrolHolding(fund, 5_00000000L, 0L);
    strike(fund, Nav.of(10_00000000L, CurrencyCode.MXN));
    navCache.refreshAll();

    assertThat(summaries.summaryOf(acme.organizationId).contributionsThisPeriod())
        .isEqualTo(Money.of(0, CurrencyCode.MXN));
  }

  // -------------------------------------------------------------------------------------------
  // "Completes under 2 seconds for a 10,000-employee organization."
  // -------------------------------------------------------------------------------------------

  /**
   * <b>The bound is the acceptance criterion's own two seconds, not a tighter number.</b> A tight
   * wall-clock assertion in CI is intermittent and about money, which is the worst failure mode
   * available (AGENTS.md §4). What would actually break this is a round trip per participant, and
   * that is orders of magnitude, not percent: 10,000 sequential ledger calls could not finish in
   * two seconds on any hardware this runs on.
   *
   * <p>The correctness half is asserted too, against the participants that do hold units — a fast
   * summary that lost people would pass a timing assertion on its own.
   */
  @Test
  @DisplayName("a 10,000-employee organization is summarised in under two seconds")
  void aTenThousandEmployeeOrganizationIsSummarisedInUnderTwoSeconds() {
    Fund fund = registerFund();
    Employer acme = registerEmployer();
    ParticipantId sampleHolder = acme.enrolHoldingCrowd(fund, HOLDERS_AT_SCALE);
    acme.enrolCrowd(PARTICIPANTS_AT_SCALE - HOLDERS_AT_SCALE);
    Nav nav = Nav.of(11_25000000L, CurrencyCode.MXN);
    strike(fund, nav);
    navCache.refreshAll();

    long startedAt = System.nanoTime();
    OrganizationSummary summary = summaries.summaryOf(acme.organizationId);
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    // Every holder holds the same lot, so the tie can be stated in closed form rather than by
    // reading ten thousand balances back one at a time — which would time this test, not the
    // endpoint. The per-lot figure is the one the balance endpoint itself serves, asserted below.
    Money perHolder = Money.value(Units.of(UNITS_PER_HOLDER), nav);
    assertThat(balances.balanceOf(sampleHolder).investedValue()).isEqualTo(perHolder);

    assertThat(summary.totalParticipants()).isEqualTo(PARTICIPANTS_AT_SCALE);
    assertThat(summary.funds())
        .filteredOn(total -> total.fundId().equals(fund.fundId))
        .singleElement()
        .satisfies(
            total -> {
              assertThat(total.totalUnits())
                  .isEqualTo(Units.of(UNITS_PER_HOLDER * HOLDERS_AT_SCALE));
              assertThat(total.totalValue())
                  .isEqualTo(Money.of(perHolder.cents() * HOLDERS_AT_SCALE, CurrencyCode.MXN));
            });
    assertThat(elapsedMillis)
        .as(
            "%d participants, %d of them holding units, summarised in %d ms",
            PARTICIPANTS_AT_SCALE, HOLDERS_AT_SCALE, elapsedMillis)
        .isLessThan(2_000);
  }

  // -------------------------------------------------------------------------------------------
  // Fixture helpers. Everything below creates ids this test alone asserts over.
  // -------------------------------------------------------------------------------------------

  private record Fund(FundId fundId, long unitLedgerId, AccountId unitsInIssue) {}

  /** Today in the platform's zone — the same reading the service takes to fix the period. */
  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneId.of("America/Mexico_City"));
  }

  /** The sum of what the balance endpoint itself serves each of {@code people}. */
  private Money sumOfBalances(List<ParticipantId> people) {
    Money total = Money.of(0, CurrencyCode.MXN);
    for (ParticipantId participantId : people) {
      total = total.plus(balances.balanceOf(participantId).investedValue());
    }
    return total;
  }

  /** The one permanent fund this suite prices, registered idempotently. */
  private Fund registerFund() {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            ON CONFLICT (fund_id) DO NOTHING
            """)
        .param("fundId", SUMMARY_FUND.value())
        .param("unitLedgerId", SUMMARY_UNIT_LEDGER)
        .param("inceptionDate", LocalDate.of(2020, 1, 1))
        .param("now", now)
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, 'Organization summary test fund',
                    'CAJA_DE_AHORRO', 'ACTIVE', :now)
            ON CONFLICT DO NOTHING
            """)
        .param("fundId", SUMMARY_FUND.value())
        .param("effectiveFrom", LocalDate.of(2020, 1, 1))
        .param("now", now)
        .update();

    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, SUMMARY_FUND.toString());
    ledger.createAccount(
        new CreateAccountCommand(
            unitsInIssue, SUMMARY_UNIT_LEDGER, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    return new Fund(SUMMARY_FUND, SUMMARY_UNIT_LEDGER, unitsInIssue);
  }

  /**
   * Strikes {@code nav} the day after this fund's newest price, so it is always the live one.
   *
   * <p>The date is read from the table rather than chosen: the cache primes from the latest live
   * NAV per fund with no date bound, so a strike that did not move forward would leave an earlier
   * run's price live and this suite asserting against a number nobody struck.
   */
  private LocalDate strike(Fund fund, Nav nav) {
    LocalDate valuationDate =
        jdbc.sql(
                """
                SELECT COALESCE(MAX(valuation_date) + 1, CAST(:floor AS date))
                  FROM reference.fund_nav WHERE fund_id = :fundId
                """)
            .param("fundId", fund.fundId.value())
            .param("floor", FIRST_VALUATION_DATE)
            .query(LocalDate.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, :unitsInIssue, 'MODEL_B', :now)
            """)
        .param("fundId", fund.fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", nav.scaled())
        .param("unitsInIssue", 1_000_000L * Units.SCALE)
        .param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .update();
    return valuationDate;
  }

  private Employer registerEmployer() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO master.organization (
                organization_id, tax_id_scheme, tax_id, legal_name, status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Acme S.A. de C.V.', 'ACTIVE', :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", "RFC-" + UUID.randomUUID())
        .param("now", now)
        .update();
    organizationsCreated.add(organizationId);

    String tenantId = TenantRef.derive(LEDGER_NAMESPACE, organizationId).asUnsignedDecimal();
    jdbc.sql("SELECT projection.ensure_tenant_partitions(:t)")
        .param("t", tenantId)
        .query()
        .listOfRows();
    tenantsCreated.add(tenantId);
    LocalDate today = today();
    for (LocalDate month :
        List.of(
            today.withDayOfMonth(1).minusMonths(1),
            today.withDayOfMonth(1),
            today.withDayOfMonth(1).plusMonths(1))) {
      jdbc.sql("SELECT projection.ensure_transfer_month(:t, :m)")
          .param("t", tenantId)
          .param("m", month)
          .query()
          .listOfRows();
    }
    return new Employer(organizationId, tenantId);
  }

  /** One employer, its participants, their ledger accounts and its projected cash movements. */
  private final class Employer {

    private final OrganizationId organizationId;
    private final String tenantId;
    private int nextOrdinal;

    private Employer(OrganizationId organizationId, String tenantId) {
      this.organizationId = organizationId;
      this.tenantId = tenantId;
    }

    /** Enrols a participant who has never been allotted into anything. */
    private ParticipantId enrol() {
      return ParticipantId.of(enrolRow("EMP-" + UUID.randomUUID()));
    }

    /** Enrols a participant and opens the holding accounts an allotment would have opened. */
    private ParticipantId enrolHolding(Fund fund, long vested, long unvested) {
      String internalRef = "EMP-" + UUID.randomUUID();
      UUID participantId = enrolRow(internalRef);
      issue(fund, internalRef, vested, unvested);
      return ParticipantId.of(participantId);
    }

    /**
     * Enrols {@code count} participants holding {@link #UNITS_PER_HOLDER} vested units each.
     *
     * <p>Opening an account is one round trip to a real cluster, so the accounts are opened
     * concurrently on a bounded pool — TigerBeetle batches concurrent requests, which turns
     * thousands of sequential round trips into a handful of batches. The pool is bounded rather
     * than a thread per account so the setup cannot swamp the client. The unit transfers that put
     * the units there are batched outright, which the port already supports.
     *
     * @return one of the enrolled participants, for a cross-check against the balance endpoint
     */
    private ParticipantId enrolHoldingCrowd(Fund fund, int count) {
      String prefix = "HOLDER-" + organizationId.value() + "-";
      insertParticipantCrowd(prefix, count);

      List<String> internalRefs = new ArrayList<>(count);
      for (int i = 1; i <= count; i++) {
        internalRefs.add(prefix + String.format("%06d", i));
      }
      openHoldingAccounts(fund, internalRefs);
      issueUnits(fund, internalRefs);
      return participantIdOf(internalRefs.get(0));
    }

    private void openHoldingAccounts(Fund fund, List<String> internalRefs) {
      List<Future<?>> pending = new ArrayList<>(internalRefs.size());
      ExecutorService pool = Executors.newFixedThreadPool(32);
      try {
        for (String internalRef : internalRefs) {
          AccountId holding = vestedAccount(fund, internalRef);
          pending.add(
              pool.submit(
                  () ->
                      ledger.createAccount(
                          new CreateAccountCommand(
                              holding,
                              fund.unitLedgerId,
                              LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED))));
        }
        for (Future<?> future : pending) {
          future.get();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted opening the crowd's ledger accounts", e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("a crowd ledger account could not be opened", e.getCause());
      } finally {
        pool.shutdownNow();
      }
    }

    private void issueUnits(Fund fund, List<String> internalRefs) {
      List<CreateTransferCommand> batch = new ArrayList<>(TRANSFER_BATCH);
      for (String internalRef : internalRefs) {
        batch.add(
            CreateTransferCommand.Unit.single(
                TransferId.of(
                    UUID.randomUUID().getMostSignificantBits(),
                    UUID.randomUUID().getLeastSignificantBits()),
                fund.unitsInIssue,
                vestedAccount(fund, internalRef),
                fund.unitLedgerId,
                LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
                Units.of(UNITS_PER_HOLDER)));
        if (batch.size() == TRANSFER_BATCH) {
          post(batch);
          batch.clear();
        }
      }
      post(batch);
    }

    private void post(List<CreateTransferCommand> batch) {
      if (batch.isEmpty()) {
        return;
      }
      assertThat(ledger.createTransfers(List.copyOf(batch)))
          .as("every fixture transfer must land, or the totals under test are not what they seem")
          .noneMatch(TransferResult.Rejected.class::isInstance);
    }

    private AccountId vestedAccount(Fund fund, String internalRef) {
      return accountIds.derive(
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
          new ParticipantScope(organizationId, internalRef).forFund(fund.fundId));
    }

    /** Enrols {@code count} participants who hold nothing at all. */
    private void enrolCrowd(int count) {
      if (count <= 0) {
        return;
      }
      insertParticipantCrowd("ENROLLED-" + organizationId.value() + "-", count);
    }

    /**
     * One employer contribution as {@code AllotmentService} projects it: the whole cash chain, both
     * legs, at the same value date.
     */
    private void contribute(LocalDate valueDate, long cents) {
      long idBase = 1_800_000_000_000_000_000L + (++nextOrdinal) * 1_000_000L;
      AccountId employerCollections =
          accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
      AccountId allotmentSuspense =
          accountIds.derive(
              LedgerCodes.AccountCode.ALLOTMENT_SUSPENSE, UUID.randomUUID().toString());
      AccountId fundCash =
          accountIds.derive(LedgerCodes.AccountCode.FUND_CASH, UUID.randomUUID().toString());
      insertCashTransfer(idBase, employerCollections, allotmentSuspense, cents, valueDate);
      insertCashTransfer(idBase + 1, allotmentSuspense, fundCash, cents, valueDate);
    }

    private void insertCashTransfer(
        long id, AccountId debit, AccountId credit, long cents, LocalDate valueDate) {
      jdbc.sql(
              """
              INSERT INTO projection.transfer
                (transfer_id, tenant_id, ledger_timestamp, ledger_id, debit_account_id,
                 credit_account_id, amount_scaled, code, booking_date, value_date)
              VALUES
                (CAST(:id AS numeric), :tenantId, CAST(:id AS numeric), :ledgerId,
                 CAST(:debit AS numeric), CAST(:credit AS numeric), :cents, :code,
                 :valueDate, :valueDate)
              """)
          .param("id", Long.toString(id))
          .param("tenantId", tenantId)
          .param("ledgerId", LedgerCodes.CASH_LEDGER_ID)
          .param("debit", debit.asUnsignedDecimal())
          .param("credit", credit.asUnsignedDecimal())
          .param("cents", cents)
          .param("code", LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code())
          .param("valueDate", valueDate)
          .update();
    }

    private void issue(Fund fund, String internalRef, long vested, long unvested) {
      String scope = new ParticipantScope(organizationId, internalRef).forFund(fund.fundId);
      issueInto(fund, scope, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, vested);
      issueInto(fund, scope, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, unvested);
    }

    private void issueInto(
        Fund fund, String scope, LedgerCodes.AccountCode code, long amountScaled) {
      if (amountScaled <= 0) {
        return;
      }
      AccountId holding = accountIds.derive(code, scope);
      ledger.createAccount(new CreateAccountCommand(holding, fund.unitLedgerId, code));
      ledger.createTransfer(
          CreateTransferCommand.Unit.single(
              TransferId.of(
                  UUID.randomUUID().getMostSignificantBits(),
                  UUID.randomUUID().getLeastSignificantBits()),
              fund.unitsInIssue,
              holding,
              fund.unitLedgerId,
              LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
              Units.of(amountScaled)));
    }

    private UUID enrolRow(String internalRef) {
      insertParticipant(internalRef);
      return participantIdOf(internalRef).value();
    }

    private void insertParticipant(String internalRef) {
      Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
      jdbc.sql(
              """
              INSERT INTO master.participant (
                  organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                  participant_status, employment_status, employment_start_date, enrolment_date,
                  created_at, updated_at)
              VALUES (:organizationId, :internalRef, 'PLAN-A', :payrollRef,
                      'ACTIVE', 'ACTIVE', DATE '2024-01-15', DATE '2024-02-01', :now, :now)
              """)
          .param("organizationId", organizationId.value())
          .param("internalRef", internalRef)
          .param("payrollRef", "PR-" + internalRef)
          .param("now", now)
          .update();
    }

    /**
     * {@code count} participants sharing an internal-ref prefix, in one statement.
     *
     * <p>{@code generate_series} rather than a bound array: a ten-thousand-element parameter is a
     * large round trip for no gain, and the refs are formed the same way the caller re-forms them
     * to derive account ids.
     */
    private void insertParticipantCrowd(String prefix, int count) {
      Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
      jdbc.sql(
              """
              INSERT INTO master.participant (
                  organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                  participant_status, employment_status, employment_start_date, enrolment_date,
                  created_at, updated_at)
              SELECT :organizationId, :prefix || lpad(g::text, 6, '0'), 'PLAN-A',
                     'PR-' || :prefix || lpad(g::text, 6, '0'),
                     'ACTIVE', 'ACTIVE', DATE '2024-01-15', DATE '2024-02-01', :now, :now
                FROM generate_series(1, :count) AS g
              """)
          .param("organizationId", organizationId.value())
          .param("prefix", prefix)
          .param("count", count)
          .param("now", now)
          .update();
    }

    private ParticipantId participantIdOf(String internalRef) {
      return ParticipantId.of(
          jdbc.sql(
                  """
                  SELECT participant_id FROM master.participant
                   WHERE organization_id = :organizationId
                     AND participant_internal_ref = :internalRef
                  """)
              .param("organizationId", organizationId.value())
              .param("internalRef", internalRef)
              .query(UUID.class)
              .single());
    }
  }
}
