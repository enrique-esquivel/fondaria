package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.ProjectionCatchUpProperties;
import dev.fondaria.eodjob.config.ReconciliationProperties;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.eodjob.schedule.CloseWindow;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled pass waits for the projection to catch up before it reconciles (ADR-0038).
 *
 * <p>The day here is a <b>falling-NAV day</b> (docs/ENGINEERING-RULES.md §4): the fund's price
 * drops from 10.00 to 9.50, and the day's contribution of MXN 1,900.00 is allotted at the lower
 * price — 200 units, where it would have been 190 the day before. A price never reaches the tie-out
 * (units are compared with units), and it must not reach the catch-up check either: what the close
 * waits for is the ledger's last <i>transfer</i>, and a NAV strike posts none. So the close waits
 * only for the allotment's events, and the pass that follows ties at the new unit count.
 */
class ReconciliationRunnerTest {

  private static final UUID NAMESPACE = UUID.fromString("6f0f1b3e-2f7a-4f24-9c62-1a3d8c7b5e11");
  private static final long FUND_LEDGER = 1_001L;
  private static final Instant DURING_CLOSE = Instant.parse("2026-09-04T23:30:00Z"); // 17:30 CDMX
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 4);

  /** The units held before the day: 900.00000000. */
  private static final long OPENING_UNITS = 900 * Units.SCALE;

  /** MXN 1,900.00 at the day's falling NAV of 9.50 is exactly 200.00000000 units. */
  private static final long ALLOTTED_AT_FALLING_NAV = 200 * Units.SCALE;

  private static final Instant ALLOTMENT_POSTED = Instant.parse("2026-09-04T22:10:00Z");
  private static final long ALLOTMENT_POSTED_NANOS = 1_788_559_800_000_000_000L;
  private static final long YESTERDAY_LAST_NANOS = 1_788_473_400_000_000_000L;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(NAMESPACE);
  private final FakeLedger ledger = new FakeLedger();
  private final InMemoryDirectories.Funds funds = new InMemoryDirectories.Funds();
  private final InMemoryDirectories.Organizations organizations =
      new InMemoryDirectories.Organizations();
  private final InMemoryDirectories.Holdings holdings = new InMemoryDirectories.Holdings();
  private final InMemoryDirectories.Projection projection = new InMemoryDirectories.Projection();
  private final InMemoryDirectories.Runs runs = new InMemoryDirectories.Runs();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AtomicReference<ProjectionProgress.Snapshot> checkpoint = new AtomicReference<>();

  private final OrganizationId acme = new OrganizationId(UUID.randomUUID());
  private final FundId fund = new FundId(UUID.randomUUID());

  @Test
  @DisplayName(
      "on a falling-NAV day the close skips while the allotment's events drain, then ties at the"
          + " new unit count")
  void waitsForTheDrainThenTies() {
    ReconciliationRunner runner = runner();
    // The ledger after the day: the allotment at 9.50 has posted.
    ledgerHolds(OPENING_UNITS + ALLOTTED_AT_FALLING_NAV);
    ledger.headAt(ALLOTMENT_POSTED);
    // The projection is still on yesterday's book, and its consumers are busy draining.
    projection.withUnits(FUND_LEDGER, OPENING_UNITS);
    checkpoint.set(drainingAt(YESTERDAY_LAST_NANOS, 4));

    runner.sweep();
    runner.sweep();

    assertThat(runs.latestFor(BUSINESS_DATE))
        .as("a skipped tick writes no run, so it cannot be mistaken for a pass or a break")
        .isEmpty();
    assertThat(new ReconciliationGate(runs).isOpen(BUSINESS_DATE)).isFalse();
    assertThat(skipped()).isEqualTo(2.0);

    // The drain finishes: the projection holds the allotment, every consumer has gone quiet.
    projection.withUnits(FUND_LEDGER, OPENING_UNITS + ALLOTTED_AT_FALLING_NAV);
    checkpoint.set(drainedAt(ALLOTMENT_POSTED_NANOS));

    runner.sweep();

    ReconciliationRun run = runs.latestFor(BUSINESS_DATE).orElseThrow();
    assertThat(run.status()).isEqualTo(RunStatus.PASSED);
    assertThat(new ReconciliationGate(runs).isOpen(BUSINESS_DATE)).isTrue();
    assertThat(skipped()).as("the pass that ran is not counted as skipped").isEqualTo(2.0);
  }

  @Test
  @DisplayName(
      "a projection that stops short of the head runs the pass anyway once it is stalled, and the"
          + " drift it lost is recorded as a break")
  void aStalledProjectionStillRecordsItsBreak() {
    ReconciliationRunner runner = runner();
    ledgerHolds(OPENING_UNITS + ALLOTTED_AT_FALLING_NAV);
    ledger.headAt(ALLOTMENT_POSTED);
    projection.withUnits(FUND_LEDGER, OPENING_UNITS);
    checkpoint.set(
        new ProjectionProgress.Snapshot(
            Optional.of(YESTERDAY_LAST_NANOS), 0, Optional.of(Duration.ofMinutes(5))));

    runner.sweep();

    ReconciliationRun run = runs.latestFor(BUSINESS_DATE).orElseThrow();
    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.breakCount()).isEqualTo(1);
    assertThat(skipped()).isZero();
  }

  // ---- fixture helpers ------------------------------------------------------------------

  private double skipped() {
    return registry.get("eod_reconciliation_skipped_total").counter().count();
  }

  private static ProjectionProgress.Snapshot drainingAt(long highestNanos, long busy) {
    return new ProjectionProgress.Snapshot(
        Optional.of(highestNanos), busy, Optional.of(Duration.ofMillis(150)));
  }

  private static ProjectionProgress.Snapshot drainedAt(long highestNanos) {
    return new ProjectionProgress.Snapshot(
        Optional.of(highestNanos), 0, Optional.of(Duration.ofSeconds(20)));
  }

  /** One participant holding {@code unitsScaled}, all vested, and the matching units in issue. */
  private void ledgerHolds(long unitsScaled) {
    HoldingKey key = new HoldingKey(acme, "P-001", fund);
    holdings.with(key);
    organizations.with(acme);
    funds.with(new RegisteredFund(fund, FUND_LEDGER));
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, key.scope()),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        FUND_LEDGER,
        unitsScaled);
    ledger.putUnits(
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fund.value().toString()),
        LedgerCodes.AccountCode.UNITS_IN_ISSUE,
        FUND_LEDGER,
        unitsScaled);
  }

  private ReconciliationRunner runner() {
    Clock clock = Clock.fixed(DURING_CLOSE, ZoneId.of("UTC"));
    BusinessCalendar calendar =
        new BusinessCalendar(
            ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.<LocalDate>of());
    ReconciliationService service =
        new ReconciliationService(
            ledger,
            accountIds,
            funds,
            organizations,
            holdings,
            projection,
            runs,
            new ReconciliationProperties(Duration.ofSeconds(20), 8_189, 50_000),
            calendar,
            clock);
    CloseWindow window =
        CloseWindow.always(ZoneId.of("America/Mexico_City"), calendar, clock, Optional::empty);
    ProjectionCatchUp catchUp =
        new ProjectionCatchUp(
            ledger,
            (headNanos, quiet) -> checkpoint.get(),
            new ProjectionCatchUpProperties(Duration.ofSeconds(15), Duration.ofMinutes(2)));
    return new ReconciliationRunner(service, new ReconciliationMetrics(registry), window, catchUp);
  }
}
