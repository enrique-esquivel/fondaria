package dev.fondaria.balanceapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.balance.ParticipantBalance;
import dev.fondaria.balanceapi.participant.CachingParticipantDirectory;
import dev.fondaria.balanceapi.participant.JdbcParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import dev.fondaria.valuation.InMemoryNavCache;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The balance read against a real PostgreSQL and a real TigerBeetle (Task 6.2).
 *
 * <p><b>Every test generates its own organization, fund, participant and ledger namespace.</b>
 * Fixtures are reused across runs and TigerBeetle cannot be reset at all (docs/ENGINEERING-RULES.md
 * §4), so nothing here counts rows over a whole table or a whole ledger — every assertion is over
 * ids this test created, and a fixed namespace would let a run pass on accounts a previous run
 * opened.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BalanceIntegrationTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  /** Rows per statement when seeding and removing the 100,000-participant crowd. */
  private static final int CROWD_CHUNK = 10_000;

  @Autowired private BalanceService balances;
  @Autowired private JdbcClient jdbc;
  @Autowired private LedgerPort ledger;
  @Autowired private InMemoryNavCache navCache;
  @Autowired private Clock clock;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);

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

    // Not used by the balance path at all — the context declines to start without it, because
    // MovementController's cursor signing key has no default (Task 6.3, CursorProperties).
    registry.add("fondaria.query.cursor-secret", () -> "a-balance-test-cursor-signing-key");

    // An hour, so the convergence sweep never fires mid-test and makes an assertion about a
    // price intermittent. Tests refresh the cache explicitly instead.
    registry.add("fondaria.nav-cache.refresh-interval", () -> "1h");
  }

  // -------------------------------------------------------------------------------------------
  // The core criterion: the balance is units × NAV, exactly.
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a participant's balance is units × NAV, read from the ledger and the NAV cache")
  void aBalanceIsUnitsTimesNav() {
    Fixture fixture = enrolled();
    long vested = 1_234_56789012L;
    long unvested = 98_76543210L;
    issue(fixture, vested, unvested);
    Nav nav = Nav.of(147_38300000L, CurrencyCode.MXN);
    strike(fixture.fundId, nav, LocalDate.of(2026, 8, 31));
    navCache.refreshAll();

    ParticipantBalance balance = balances.balanceOf(fixture.participantId);

    assertThat(balance.positions())
        .singleElement()
        .satisfies(
            position -> {
              assertThat(position.fundId()).isEqualTo(fixture.fundId);
              assertThat(position.vestedUnits()).isEqualTo(Units.of(vested));
              assertThat(position.unvestedUnits()).isEqualTo(Units.of(unvested));
            });
    assertThat(balance.investedValue())
        .isEqualTo(Money.value(Units.of(vested), nav).plus(Money.value(Units.of(unvested), nav)));
  }

  /**
   * A NAV that falls must move every balance down. A suite that only ever raises the price passes
   * on a broken implementation (docs/ENGINEERING-RULES.md §4).
   */
  @Test
  @DisplayName("a falling NAV lowers the balance, and is never clamped at the earlier value")
  void aFallingNavLowersTheBalance() {
    Fixture fixture = enrolled();
    long units = 1_000_00000000L;
    issue(fixture, units, 0);

    strike(fixture.fundId, Nav.of(20_00000000L, CurrencyCode.MXN), LocalDate.of(2026, 8, 30));
    navCache.refreshAll();
    Money high = balances.balanceOf(fixture.participantId).investedValue();

    // The next day's price is lower. Same units, fewer pesos.
    strike(fixture.fundId, Nav.of(8_50000000L, CurrencyCode.MXN), LocalDate.of(2026, 8, 31));
    navCache.refreshAll();
    Money low = balances.balanceOf(fixture.participantId).investedValue();

    assertThat(high).isEqualTo(Money.of(2_000_000L, CurrencyCode.MXN));
    assertThat(low).isEqualTo(Money.of(850_000L, CurrencyCode.MXN));
    assertThat(low.cents()).isLessThan(high.cents());
  }

  // -------------------------------------------------------------------------------------------
  // "With PostgreSQL stopped, the balance endpoint still works."
  // -------------------------------------------------------------------------------------------

  /**
   * Proved against a dedicated connection pool this test owns and closes, rather than by stopping
   * the shared PostgreSQL container.
   *
   * <p><b>The fixture is a per-JVM singleton reused by every other test class</b>
   * (docs/ENGINEERING-RULES.md §4), so stopping it would break unrelated suites rather than prove
   * anything about this one. Closing a pool is the same observable condition for the service —
   * every database call fails — and it isolates the proof to the code path under test.
   *
   * <p>What is being proved is precise: once a participant has been resolved, the balance path
   * holds no dependency on PostgreSQL at all. Units come from the ledger and the price from memory.
   */
  @Test
  @DisplayName("a resolved participant's balance is still served with PostgreSQL unreachable")
  void aBalanceIsStillServedWithPostgresUnreachable() {
    Fixture fixture = enrolled();
    issue(fixture, 500_00000000L, 0);
    strike(fixture.fundId, Nav.of(10_00000000L, CurrencyCode.MXN), LocalDate.of(2026, 8, 31));
    navCache.refreshAll();

    HikariDataSource ownPool = ownConnectionPool();
    CachingParticipantDirectory directory =
        new CachingParticipantDirectory(
            new JdbcParticipantDirectory(JdbcClient.create(ownPool)), 1_000);
    BalanceService service = new BalanceService(directory, ledger, navCache, accountIds, clock);

    Money before = service.balanceOf(fixture.participantId).investedValue();
    assertThat(before).isEqualTo(Money.of(500_000L, CurrencyCode.MXN));

    // PostgreSQL is now unreachable as far as this service is concerned.
    ownPool.close();
    assertThatThrownBy(() -> JdbcClient.create(ownPool).sql("SELECT 1").query().singleValue())
        .as("the pool really is unusable")
        .isInstanceOf(Exception.class);

    Money after = service.balanceOf(fixture.participantId).investedValue();
    assertThat(after).isEqualTo(before);

    // And a participant never resolved before the outage fails honestly rather than
    // reporting a zero balance. DataAccessException is what BalanceExceptionHandler maps to
    // a 503 (not the 500 it fell through to before the adversarial review caught it).
    assertThatThrownBy(() -> service.balanceOf(ParticipantId.of(UUID.randomUUID())))
        .isInstanceOf(DataAccessException.class);
  }

  // -------------------------------------------------------------------------------------------
  // "A test at 100,000 participants confirms read cost does not grow with participant count."
  // -------------------------------------------------------------------------------------------

  /**
   * The structural argument is in {@code BalanceServiceTest} — one batched ledger call of two
   * account ids per fund, with nothing in the path iterating participants. This is the empirical
   * half: the same read against a {@code master.participant} holding 100,000 rows returns the same
   * answer, and the resolution stays an index lookup rather than degrading into a scan.
   *
   * <p><b>The timing assertion is deliberately loose.</b> A tight wall-clock bound in CI is
   * intermittent and about money, which is the worst failure mode available
   * (docs/ENGINEERING-RULES.md §4); what would actually break here is a sequential scan, and that
   * is orders of magnitude, not percent.
   */
  @Test
  @DisplayName("a balance read is unchanged, and stays an index lookup, at 100,000 participants")
  void readCostDoesNotGrowWithParticipantCount() {
    Fixture fixture = enrolled();
    issue(fixture, 250_00000000L, 0);
    strike(fixture.fundId, Nav.of(4_00000000L, CurrencyCode.MXN), LocalDate.of(2026, 8, 31));
    navCache.refreshAll();

    Money atSmallScale = balances.balanceOf(fixture.participantId).investedValue();
    assertThat(atSmallScale).isEqualTo(Money.of(100_000L, CurrencyCode.MXN));

    String crowdPrefix = enrolCrowd(fixture.organizationId, 100_000);
    try {
      // A cold directory, so the 100,000-row table is genuinely queried rather than answered
      // from the cache the earlier read populated.
      BalanceService cold =
          new BalanceService(
              new CachingParticipantDirectory(new JdbcParticipantDirectory(jdbc), 1_000),
              ledger,
              navCache,
              accountIds,
              clock);

      long startedAt = System.nanoTime();
      Money atScale = cold.balanceOf(fixture.participantId).investedValue();
      long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(atScale).isEqualTo(atSmallScale);
      assertThat(elapsedMillis)
          .as("a scan over 100,000 rows would be orders of magnitude slower than an index lookup")
          .isLessThan(2_000);

      assertThat(explainParticipantLookup())
          .as("participant_id_unique must serve the resolution")
          .contains("Index");
    } finally {
      // The fixture is reused across runs (docs/ENGINEERING-RULES.md §4). Leaving 100,000 rows
      // behind on every
      // run would grow the shared database without bound and degrade every other suite that
      // touches this table, so the crowd is removed even when an assertion above fails.
      removeCrowd(crowdPrefix);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The V22 migration itself.
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("every participant is assigned a distinct participant_id by the column default")
  void everyParticipantIsAssignedADistinctIdWithoutApplicationCode() {
    OrganizationId organization = registerOrganization();
    List<UUID> assigned = new ArrayList<>();
    for (int i = 0; i < 25; i++) {
      // Note the INSERT never names participant_id: the previous application version does not
      // know the column exists, and must keep working (docs/ENGINEERING-RULES.md §4,
      // expand/contract).
      assigned.add(enrolParticipant(organization, "EMP-" + UUID.randomUUID()));
    }
    assertThat(assigned).doesNotContainNull().doesNotHaveDuplicates();
  }

  @Test
  @DisplayName(
      "participant_id resolves back to the organization and internal ref it was assigned to")
  void participantIdResolvesToItsScope() {
    OrganizationId organization = registerOrganization();
    String internalRef = "EMP-" + UUID.randomUUID();
    UUID participantId = enrolParticipant(organization, internalRef);

    assertThat(new JdbcParticipantDirectory(jdbc).find(ParticipantId.of(participantId)))
        .contains(new ParticipantScope(organization, internalRef));
  }

  @Test
  @DisplayName("an unknown participant id resolves to nothing, never to an empty scope")
  void anUnknownParticipantIdResolvesToNothing() {
    assertThat(new JdbcParticipantDirectory(jdbc).find(ParticipantId.of(UUID.randomUUID())))
        .isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture helpers. Everything below creates ids this test alone asserts over.
  // -------------------------------------------------------------------------------------------

  private record Fixture(
      OrganizationId organizationId,
      String internalRef,
      ParticipantId participantId,
      FundId fundId,
      long unitLedgerId) {}

  /** An organization, a fund and one participant enrolled into it. */
  private Fixture enrolled() {
    OrganizationId organization = registerOrganization();
    String internalRef = "EMP-" + UUID.randomUUID();
    UUID participantId = enrolParticipant(organization, internalRef);
    long unitLedgerId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000) + 1001;
    FundId fundId = registerFund(unitLedgerId);
    return new Fixture(
        organization, internalRef, ParticipantId.of(participantId), fundId, unitLedgerId);
  }

  /** Opens the participant's holding accounts and issues units into them, as an allotment would. */
  private void issue(Fixture fixture, long vestedScaled, long unvestedScaled) {
    String scope =
        fixture.organizationId.scope() + ":" + fixture.internalRef + ":" + fixture.fundId;
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fixture.fundId.toString());
    ledger.createAccount(
        new CreateAccountCommand(
            unitsInIssue, fixture.unitLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));

    issueInto(
        fixture,
        scope,
        unitsInIssue,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        vestedScaled);
    issueInto(
        fixture,
        scope,
        unitsInIssue,
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
        unvestedScaled);
  }

  private void issueInto(
      Fixture fixture,
      String scope,
      AccountId unitsInIssue,
      LedgerCodes.AccountCode code,
      long amountScaled) {
    if (amountScaled <= 0) {
      return;
    }
    AccountId holding = accountIds.derive(code, scope);
    ledger.createAccount(new CreateAccountCommand(holding, fixture.unitLedgerId, code));
    ledger.createTransfer(
        CreateTransferCommand.Unit.single(
            TransferId.of(
                UUID.randomUUID().getMostSignificantBits(),
                UUID.randomUUID().getLeastSignificantBits()),
            unitsInIssue,
            holding,
            fixture.unitLedgerId,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(amountScaled)));
  }

  private void strike(FundId fundId, Nav nav, LocalDate valuationDate) {
    jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, :unitsInIssue, 'MODEL_B', :now)
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .param("navScaled", nav.scaled())
        .param("unitsInIssue", 1_000_000L * Units.SCALE)
        .param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  /**
   * A fund and its immutable identity half, the two rows {@code ReadOnlyFundNavRepository} joins.
   */
  private FundId registerFund(long unitLedgerId) {
    FundId fundId = FundId.of(UUID.randomUUID());
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("inceptionDate", LocalDate.of(2025, 1, 1))
        .param("now", now)
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", LocalDate.of(2025, 1, 1))
        .param("name", "Balance test fund " + fundId.value())
        .param("now", now)
        .update();
    return fundId;
  }

  private OrganizationId registerOrganization() {
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
    return organizationId;
  }

  /**
   * @return the {@code participant_id} the column default assigned
   */
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

  /**
   * Bulk-enrols {@code count} participants, so the resolution runs against a large table.
   *
   * @return the internal-ref prefix they share, for {@link #removeCrowd}
   */
  private String enrolCrowd(OrganizationId organizationId, int count) {
    Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
    String prefix = "CROWD-" + UUID.randomUUID() + "-";
    // In chunks: every statement through this JdbcClient runs under the service's query timeout
    // (5 s), and one 100,000-row insert can exceed it on a CI runner, cancelling the setup before
    // anything is measured.
    for (int from = 1; from <= count; from += CROWD_CHUNK) {
      jdbc.sql(
              """
              INSERT INTO master.participant (
                  organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                  participant_status, employment_status, employment_start_date, enrolment_date,
                  created_at, updated_at)
              SELECT :organizationId, :prefix || g, 'PLAN-A', :prefix || 'PR' || g,
                     'ACTIVE', 'ACTIVE', DATE '2024-01-15', DATE '2024-02-01', :now, :now
                FROM generate_series(:from, :to) AS g
              """)
          .param("organizationId", organizationId.value())
          .param("prefix", prefix)
          .param("from", from)
          .param("to", Math.min(from + CROWD_CHUNK - 1, count))
          .param("now", now)
          .update();
    }
    jdbc.sql("ANALYZE master.participant").update();
    return prefix;
  }

  /** Removes exactly the rows {@link #enrolCrowd} inserted, by their own unique prefix. */
  private void removeCrowd(String prefix) {
    // In chunks, for the same query-timeout reason as enrolCrowd.
    int deleted;
    do {
      deleted =
          jdbc.sql(
                  """
                  DELETE FROM master.participant
                   WHERE ctid IN (SELECT ctid FROM master.participant
                                   WHERE participant_internal_ref LIKE :prefix || '%'
                                   LIMIT :chunk)
                  """)
              .param("prefix", prefix)
              .param("chunk", CROWD_CHUNK)
              .update();
    } while (deleted > 0);
    jdbc.sql("ANALYZE master.participant").update();
  }

  private String explainParticipantLookup() {
    return String.join(
        "\n",
        jdbc.sql(
                "EXPLAIN SELECT organization_id, participant_internal_ref FROM master.participant"
                    + " WHERE participant_id = :participantId")
            .param("participantId", UUID.randomUUID())
            .query(String.class)
            .list());
  }

  /** A pool this test owns, so closing it cannot disturb the shared application context. */
  private static HikariDataSource ownConnectionPool() {
    PostgresFixture postgres = PostgresFixture.getInstance();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(2);
    config.setPoolName("balance-api-postgres-outage-test");
    return new HikariDataSource(config);
  }
}
