package dev.fondaria.balanceapi.asof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The balance-as-of read against a real PostgreSQL, the real partitioned {@code
 * projection.participant_units} and the real append-only {@code reference.fund_nav} (Task 6.4).
 *
 * <h2>How this suite leaves the shared database as it found it</h2>
 *
 * <p>Fixtures are reused across runs (AGENTS.md §4), so every run creates its own organization,
 * participant and tenant partition, and asserts only over rows it inserted. The partition is
 * <b>dropped</b> afterwards rather than deleted from: {@code projection.participant_units} is
 * append-only and carries a {@code BEFORE DELETE} trigger that raises for every role, owner
 * included (V21).
 *
 * <p><b>The two funds, by contrast, are fixed and registered idempotently.</b> {@code
 * reference.fund_nav} is append-only too, and its rows carry a foreign key to {@code
 * reference.fund_identity} — so a fund this suite priced can never afterwards be deregistered, and
 * a fresh fund id per run would grow the registry without bound. That is not harmless: the
 * candidate fund set of both this endpoint and the movement history is the whole registry, two and
 * four query branches per fund respectively, which is exactly the slow creep {@code
 * MovementHistoryIntegrationTest} cleans up to avoid. Two permanent rows, once, is the bounded
 * version of the same trade.
 *
 * <p><b>Valuation dates walk backwards, thirty days at a time.</b> With the fund ids fixed, {@code
 * fund_nav_fund_date_original_unique} would make a second run collide on dates an earlier one used,
 * so each run needs a window of its own — deterministic and collision-free by construction, rather
 * than random and collision-free by luck. <b>Backwards, not forwards</b>, because these two funds'
 * {@code fund_nav} rows are as permanent as the funds themselves: a window that advanced would,
 * after a dozen runs, leave their newest struck price in the <i>future</i>, and {@code
 * FundNavRepository#findLatestLivePerFund} — which every balance-api context primes its NAV cache
 * from — has no date bound. Walking back from 2020 pins the newest price these funds carry to the
 * first run that ever touched this database. The rows themselves do accumulate, a handful per run,
 * and cannot be removed; that is the price of a priced fund, and the table is tiny.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AsOfBalanceIntegrationTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  /** Fixed for the reason in this class's javadoc: a priced fund can never be deregistered. */
  private static final FundId FUND_A =
      FundId.of(UUID.fromString("a5f0d1e2-6c4b-4f7a-9d31-000000000064"));

  private static final FundId FUND_B =
      FundId.of(UUID.fromString("b5f0d1e2-6c4b-4f7a-9d31-000000000064"));

  private static final long LEDGER_A = 990_064L;
  private static final long LEDGER_B = 990_065L;

  /**
   * Rows loaded into this run's own {@code participant_units} partition for the scale case.
   *
   * <p>The acceptance criterion names ten million. That default is carried here rather than in the
   * assertion, so a local run can shrink it ({@code -Das-of.unitRows=200000}) without the criterion
   * quietly changing: the plan assertion and the ten-millisecond budget hold at every size, and it
   * is the size that makes them mean something.
   */
  private static final int DEFAULT_UNIT_ROWS = 10_000_000;

  /** Change dates per synthetic account, so the load is deep as well as wide. */
  private static final int DATES_PER_ACCOUNT = 50;

  /** Rows per {@code INSERT}: small enough that no statement nears the production query timeout. */
  private static final int LOAD_CHUNK_ROWS = 200_000;

  /** The measured figure is the median of this many timed calls, after the same many warm-ups. */
  private static final int TIMED_CALLS = 21;

  private static final long TEN_MILLIS_NANOS = 10_000_000L;

  @Autowired private AsOfBalanceService balances;
  @Autowired private HistoricUnitsRepository holdings;
  @Autowired private FundDirectory funds;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private Clock clock;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final List<String> tenantsCreated = new ArrayList<>();

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    // The context builds a ledger client at startup even though this endpoint never reads the
    // ledger — a historical holding comes from the projection, which is the whole point of it.
    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);
    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);
    registry.add("fondaria.query.cursor-secret", () -> "an-integration-test-cursor-signing-key");
    registry.add("fondaria.nav-cache.refresh-interval", () -> "1h");
  }

  @AfterEach
  void dropTenantPartitions() {
    for (String tenantId : tenantsCreated) {
      String slug = slugOf(tenantId);
      jdbc.sql("DROP TABLE IF EXISTS projection.participant_units_t_" + slug + " CASCADE").update();
      jdbc.sql("DROP TABLE IF EXISTS projection.transfer_t_" + slug + " CASCADE").update();
      jdbc.sql("DELETE FROM projection.partitioned_tenant WHERE tenant_id = :t")
          .param("t", tenantId)
          .update();
    }
    tenantsCreated.clear();
  }

  // -------------------------------------------------------------------------------------------
  // "Correct value on an activity date and on a gap date."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("an activity date and a gap date are both valued at that day's own NAV")
  void anActivityDateAndAGapDateAreBothValued() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    LocalDate gap = opened.plusDays(5);
    LocalDate changed = opened.plusDays(10);

    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        1_000_00000000L);
    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
        opened,
        200_00000000L);
    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        changed,
        1_500_00000000L);

    // A falling series, deliberately: a suite that only ever rises passes on a broken
    // implementation (AGENTS.md §4). The gap date is the low point.
    long navOpened = strike(FUND_A, opened, 12_50000000L);
    long navGap = strike(FUND_A, gap, 9_75000000L);
    long navChanged = strike(FUND_A, changed, 11_00000000L);

    AsOfBalance onOpening = balances.balanceAsOf(fixture.participantId, opened);
    assertThat(units(onOpening)).isEqualTo(Units.of(1_200_00000000L));
    assertThat(onOpening.positions().get(0).nav().navVersionId()).isEqualTo(navOpened);
    assertThat(onOpening.value())
        .isEqualTo(Money.value(Units.of(1_200_00000000L), Nav.of(12_50000000L, currency())));

    AsOfBalance onGap = balances.balanceAsOf(fixture.participantId, gap);
    assertThat(units(onGap))
        .as("nothing changed that day, so the holding carried forward")
        .isEqualTo(Units.of(1_200_00000000L));
    assertThat(onGap.positions().get(0).nav().navVersionId()).isEqualTo(navGap);
    assertThat(onGap.value())
        .as("a falling NAV produces a smaller value, never a clamped one")
        .isEqualTo(Money.value(Units.of(1_200_00000000L), Nav.of(9_75000000L, currency())));
    assertThat(onGap.value().cents()).isLessThan(onOpening.value().cents());

    AsOfBalance onChange = balances.balanceAsOf(fixture.participantId, changed);
    assertThat(units(onChange)).isEqualTo(Units.of(1_700_00000000L));
    assertThat(onChange.positions().get(0).nav().navVersionId()).isEqualTo(navChanged);
    assertThat(onChange.value())
        .isEqualTo(Money.value(Units.of(1_700_00000000L), Nav.of(11_00000000L, currency())));
  }

  @Test
  @DisplayName("each fund is a position of its own, and one not yet held is absent")
  void fundsAreReportedSeparately() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    LocalDate later = opened.plusDays(10);

    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        100_00000000L);
    holdingRow(
        fixture, FUND_B, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, later, 400_00000000L);
    strike(FUND_A, opened, 10_00000000L);
    strike(FUND_A, later, 10_00000000L);
    strike(FUND_B, later, 5_00000000L);

    assertThat(balances.balanceAsOf(fixture.participantId, opened).positions())
        .extracting(AsOfPosition::fundId)
        .containsExactly(FUND_A);

    assertThat(balances.balanceAsOf(fixture.participantId, later).positions())
        .extracting(AsOfPosition::fundId)
        .containsExactlyInAnyOrder(FUND_A, FUND_B);
  }

  // -------------------------------------------------------------------------------------------
  // "Date before first contribution → 404."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a date before the first contribution is not found, never a zero balance")
  void aDateBeforeTheFirstContributionIsNotFound() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        100_00000000L);
    strike(FUND_A, opened.minusDays(1), 10_00000000L);

    assertThatThrownBy(() -> balances.balanceAsOf(fixture.participantId, opened.minusDays(1)))
        .as("a price existed for that day; what did not exist is a holding")
        .isInstanceOf(NoHoldingOnRecordException.class);
  }

  @Test
  @DisplayName("a holding redeemed to zero is a zero balance, which is a different claim")
  void aFullyRedeemedHoldingIsZeroAndNotNotFound() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    LocalDate closed = opened.plusDays(10);
    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        100_00000000L);
    holdingRow(fixture, FUND_A, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, closed, 0L);

    AsOfBalance balance = balances.balanceAsOf(fixture.participantId, closed);

    assertThat(balance.positions()).isEmpty();
    assertThat(balance.value().cents()).isZero();
  }

  // -------------------------------------------------------------------------------------------
  // "A superseded NAV date returns the corrected value and flags the correction."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a corrected NAV date is valued at the correction, and says it was corrected")
  void aCorrectedNavDateIsValuedAtTheCorrection() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        1_000_00000000L);

    long original = strike(FUND_A, opened, 12_50000000L);
    long correction = correct(FUND_A, opened, 9_00000000L, original);

    AsOfPosition position = balances.balanceAsOf(fixture.participantId, opened).positions().get(0);

    assertThat(position.nav().navVersionId())
        .as("the live row is the one nothing supersedes, not the one holding a null pointer")
        .isEqualTo(correction);
    assertThat(position.corrected()).isTrue();
    assertThat(position.value())
        .isEqualTo(Money.value(Units.of(1_000_00000000L), Nav.of(9_00000000L, currency())));
    assertThat(position.value().cents())
        .as("the corrected price is lower, and the balance follows it down")
        .isLessThan(
            Money.value(Units.of(1_000_00000000L), Nav.of(12_50000000L, currency())).cents());
  }

  // -------------------------------------------------------------------------------------------
  // "Under 10 ms at 10 million participant_units rows."
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the holding query is an index scan and stays under 10 ms at ten million rows")
  void theHoldingQueryStaysUnderTenMillisecondsAtScale() {
    Fixture fixture = enrolled();
    LocalDate opened = fixture.firstDate;
    int rows = Integer.getInteger("as-of.unitRows", DEFAULT_UNIT_ROWS);

    holdingRow(
        fixture,
        FUND_A,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        opened,
        1_000_00000000L);
    // The inserts' own row counts, not a count(*): a full scan of ten million rows is the one
    // statement here that could genuinely approach POSTGRES_QUERY_TIMEOUT, and it would fail the
    // test for a reason that has nothing to do with the endpoint.
    long loaded = loadSyntheticHoldings(fixture, opened, rows);
    assertThat(loaded).isEqualTo(rows);

    List<HoldingAccountKey> accounts = holdingAccounts(fixture);
    LocalDate asOf = opened.plusDays(DATES_PER_ACCOUNT + 5);

    String plan = explain(fixture, accounts, asOf);
    assertThat(plan)
        .as("each branch must descend the primary key, not read the participant's whole history")
        .contains("Index Scan")
        .doesNotContain("Seq Scan")
        .doesNotContain("Sort");

    // Warm up first: the criterion is about the query's cost, and the first call of a run also
    // pays for a connection, a parse and a cold buffer cache. The median of the timed calls is
    // then reported rather than the mean, so one scheduling hiccup cannot decide the result.
    for (int i = 0; i < TIMED_CALLS; i++) {
      holdings.holdingsAsOf(fixture.tenantId, accounts, asOf);
    }
    long[] elapsed = new long[TIMED_CALLS];
    for (int i = 0; i < TIMED_CALLS; i++) {
      long start = System.nanoTime();
      List<HistoricHolding> found = holdings.holdingsAsOf(fixture.tenantId, accounts, asOf);
      elapsed[i] = System.nanoTime() - start;
      assertThat(found).as("the participant's own holding is found every time").isNotEmpty();
    }
    java.util.Arrays.sort(elapsed);
    long median = elapsed[TIMED_CALLS / 2];
    System.out.printf(
        "as-of holding query over %,d participant_units rows: median %.3f ms, best %.3f ms,"
            + " worst %.3f ms%n",
        loaded, median / 1e6, elapsed[0] / 1e6, elapsed[TIMED_CALLS - 1] / 1e6);

    assertThat(median).isLessThan(TEN_MILLIS_NANOS);
  }

  // -------------------------------------------------------------------------------------------

  /** The participant's own holding accounts across both funds — what the service derives. */
  private List<HoldingAccountKey> holdingAccounts(Fixture fixture) {
    List<HoldingAccountKey> accounts = new ArrayList<>();
    for (var fund : funds.listFunds()) {
      if (!fund.fundId().equals(FUND_A) && !fund.fundId().equals(FUND_B)) {
        continue;
      }
      for (LedgerCodes.AccountCode code :
          List.of(
              LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
              LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED)) {
        accounts.add(
            new HoldingAccountKey(
                accountIds.derive(code, fixture.scope().forFund(fund.fundId())).asUnsignedDecimal(),
                fund.unitLedgerId()));
      }
    }
    return accounts;
  }

  /**
   * {@code EXPLAIN} of the statement the repository really runs, built from the production builder
   * rather than a hand-written approximation that would prune more easily than the real query.
   */
  private String explain(Fixture fixture, List<HoldingAccountKey> accounts, LocalDate asOf) {
    HistoricUnitsStatement statement =
        HistoricUnitsStatement.build(fixture.tenantId, accounts, asOf);
    var spec = jdbc.sql("EXPLAIN " + statement.sql());
    for (var parameter : statement.parameters().entrySet()) {
      spec = spec.param(parameter.getKey(), parameter.getValue());
    }
    return String.join("\n", spec.query(String.class).list());
  }

  /**
   * Bulk-loads {@code rows} holdings for synthetic accounts into this run's own partition, wide
   * ({@code rows / DATES_PER_ACCOUNT} accounts) and deep ({@code DATES_PER_ACCOUNT} changes each).
   *
   * <p>Synthetic account ids are small integers. Real ones are SHA-derived u128s, so the two ranges
   * cannot overlap and this run's own assertions cannot pick a filler row up by accident.
   */
  private long loadSyntheticHoldings(Fixture fixture, LocalDate from, int rows) {
    // In chunks, so no single statement approaches POSTGRES_QUERY_TIMEOUT. The alternative —
    // relaxing that timeout for the test — would mean the suite no longer runs under the
    // production setting it is meant to exercise, to save a loop.
    long inserted = 0;
    for (int loaded = 0; loaded < rows; loaded += LOAD_CHUNK_ROWS) {
      inserted +=
          jdbc.sql(
                  """
              INSERT INTO projection.participant_units
                (tenant_id, participant_id, fund_id, effective_date, units_scaled,
                 last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
              SELECT :tenantId, CAST(g / :perAccount AS numeric), :ledgerId,
                     CAST(:from AS date) + (g % :perAccount), 1000000 + g,
                     CAST(g AS numeric), 1800000000000000000 + g, NULL, '\\x00'::bytea
                FROM generate_series(:fromRow, :toRow) AS g
              """)
              .param("tenantId", fixture.tenantId)
              .param("perAccount", DATES_PER_ACCOUNT)
              .param("ledgerId", LEDGER_A)
              .param("from", from)
              .param("fromRow", loaded)
              .param("toRow", Math.min(loaded + LOAD_CHUNK_ROWS, rows) - 1)
              .update();
    }
    analyze("projection.participant_units_t_" + slugOf(fixture.tenantId));
    return inserted;
  }

  /**
   * {@code ANALYZE} on the fixture's partition, off the request-bounded client.
   *
   * <p><b>This must not run through the injected {@code JdbcClient}.</b> That one carries {@code
   * POSTGRES_QUERY_TIMEOUT} — five seconds, a <i>request</i> budget — and planning statistics over
   * ten million rows do not fit it on a shared CI runner. It fit on a developer's machine, which is
   * exactly why the failure arrived as a red PR on unrelated work: {@code ERROR: canceling
   * statement due to user request}, in fixture setup, hours after the change that exposed it.
   *
   * <p>{@code DatabaseConfiguration}'s own javadoc draws the line this crosses — "a migration is
   * not a request: bounding a table rewrite by a request-shaped budget would fail a deployment
   * rather than protect one". An {@code ANALYZE} of a ten-million-row fixture is maintenance, in
   * the same category. Note what is <i>not</i> changed: the inserts above stay chunked under the
   * production timeout deliberately, and no assertion this suite makes is relaxed — the ten
   * millisecond budget and the plan check are untouched.
   *
   * <p>Bounded rather than unlimited, all the same (AGENTS.md §4). A minute is far beyond what this
   * statement has ever taken and still fails a genuinely stuck one, instead of holding the job open
   * until the ninety-minute step timeout.
   */
  private void analyze(String partition) {
    JdbcTemplate maintenance = new JdbcTemplate(dataSource);
    maintenance.setQueryTimeout(60);
    maintenance.execute("ANALYZE " + partition);
  }

  private static Units units(AsOfBalance balance) {
    return balance.positions().get(0).units();
  }

  private static dev.fondaria.common.CurrencyCode currency() {
    return dev.fondaria.common.CurrencyCode.MXN;
  }

  private void holdingRow(
      Fixture fixture,
      FundId fundId,
      LedgerCodes.AccountCode code,
      LocalDate effectiveDate,
      long unitsScaled) {
    long unitLedgerId = fundId.equals(FUND_A) ? LEDGER_A : LEDGER_B;
    String accountId = accountIds.derive(code, fixture.scope().forFund(fundId)).asUnsignedDecimal();
    jdbc.sql(
            """
            INSERT INTO projection.participant_units
              (tenant_id, participant_id, fund_id, effective_date, units_scaled,
               last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
            VALUES (:tenantId, CAST(:accountId AS numeric), :ledgerId, :effectiveDate, :units,
                    CAST(:accountId AS numeric), :timestamp, NULL, '\\x00'::bytea)
            """)
        .param("tenantId", fixture.tenantId)
        .param("accountId", accountId)
        .param("ledgerId", unitLedgerId)
        .param("effectiveDate", effectiveDate)
        .param("units", unitsScaled)
        .param("timestamp", 1_800_000_000_000_000_000L + effectiveDate.toEpochDay())
        .update();
  }

  /** An original strike. Returns its {@code nav_version_id}. */
  private long strike(FundId fundId, LocalDate valuationDate, long navScaled) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, supersedes_id,
               reason, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 1000000000000, 'MODEL_B', NULL, NULL, :now)
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", navScaled)
        .param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .query(Long.class)
        .single();
  }

  /** A correction superseding {@code supersedesId} for the same date (Task 20.5). */
  private long correct(FundId fundId, LocalDate valuationDate, long navScaled, long supersedesId) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, supersedes_id,
               reason, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 1000000000000, 'MODEL_B', :supersedesId,
                    'restated by the administrator', :now)
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", navScaled)
        .param("supersedesId", supersedesId)
        .param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .query(Long.class)
        .single();
  }

  private Fixture enrolled() {
    OrganizationId organization = new OrganizationId(UUID.randomUUID());
    registerOrganization(organization);
    String internalRef = "EMP-" + UUID.randomUUID();
    UUID participantId = enrolParticipant(organization, internalRef);
    registerFund(FUND_A, LEDGER_A);
    registerFund(FUND_B, LEDGER_B);
    String tenantId = TenantRef.derive(LEDGER_NAMESPACE, organization).asUnsignedDecimal();

    jdbc.sql("SELECT projection.ensure_tenant_partitions(:t)")
        .param("t", tenantId)
        .query()
        .listOfRows();
    tenantsCreated.add(tenantId);

    return new Fixture(
        organization, internalRef, ParticipantId.of(participantId), tenantId, firstFreeDate());
  }

  /**
   * Thirty days before the <b>oldest</b> valuation date these two funds already carry, so a run
   * never collides with an earlier one on {@code fund_nav_fund_date_original_unique} and the newest
   * price they carry never moves into the future. See this class's javadoc.
   */
  private LocalDate firstFreeDate() {
    return jdbc.sql(
            """
            SELECT LEAST(COALESCE(MIN(valuation_date), DATE '2020-01-01'),
                         DATE '2020-01-01') - 30
              FROM reference.fund_nav
             WHERE fund_id IN (:fundA, :fundB)
            """)
        .param("fundA", FUND_A.value())
        .param("fundB", FUND_B.value())
        .query(LocalDate.class)
        .single();
  }

  private String slugOf(String tenantId) {
    return jdbc.sql("SELECT left(md5(:t), 16)").param("t", tenantId).query(String.class).single();
  }

  private void registerOrganization(OrganizationId organizationId) {
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
  }

  private UUID enrolParticipant(OrganizationId organizationId, String internalRef) {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                created_at, updated_at)
            VALUES (:organizationId, :internalRef, 'PLAN-A', :payrollRef,
                    'ACTIVE', 'ACTIVE', :employmentStart, :enrolment, :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .param("payrollRef", "PR-" + UUID.randomUUID())
        .param("employmentStart", LocalDate.of(2024, 1, 15))
        .param("enrolment", LocalDate.of(2024, 2, 1))
        .param("now", now)
        .update();
    return jdbc.sql(
            """
            SELECT participant_id FROM master.participant
             WHERE organization_id = :organizationId AND participant_internal_ref = :internalRef
            """)
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .query(UUID.class)
        .single();
  }

  /** Idempotent: these fund rows outlive the run that first created them. */
  private void registerFund(FundId fundId, long unitLedgerId) {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, DATE '2020-01-01', 1000000000, :now)
            ON CONFLICT (fund_id) DO NOTHING
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("now", now)
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, DATE '2020-01-01', 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            ON CONFLICT (fund_id, effective_from, version) DO NOTHING
            """)
        .param("fundId", fundId.value())
        .param("name", "Balance-as-of test fund " + fundId.value())
        .param("now", now)
        .update();
  }

  private record Fixture(
      OrganizationId organizationId,
      String internalRef,
      ParticipantId participantId,
      String tenantId,
      LocalDate firstDate) {

    ParticipantScope scope() {
      return new ParticipantScope(organizationId, internalRef);
    }
  }
}
