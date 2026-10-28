package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.config.SnapshotProperties;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.eodjob.schedule.CloseWindow;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the scheduler does, and — more importantly — what it declines to do.
 *
 * <p>Driven through a real {@link SnapshotService} over in-memory sources, not a stub: the runner's
 * job is deciding <i>whether</i> to run and translating what came back into meters, and both of
 * those are only meaningful against the real control flow.
 *
 * <p>This is the "and alerts" half of Task 7.4's fifth acceptance criterion. {@code
 * SnapshotServiceTest} asserts on what the service threw; nothing there asserts on what an operator
 * would actually see.
 */
class SnapshotRunnerTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(ZONE, LocalTime.of(18, 0), Set.of());
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);
  private static final Instant MIDDAY = TODAY.atTime(LocalTime.NOON).atZone(ZONE).toInstant();

  private static final SnapshotProperties PROPERTIES =
      new SnapshotProperties(Duration.ofHours(1), 100, 8_189, "snapshots", 64L * 1024 * 1024);

  private final UUID namespace = UUID.randomUUID();
  private final AccountIdDeriver accountIds = new AccountIdDeriver(namespace);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(namespace);
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = new FundId(UUID.randomUUID());
  private final long unitLedgerId = 1001L;

  private final InMemorySnapshotSources.Funds funds =
      new InMemorySnapshotSources.Funds().with(fundId, unitLedgerId);
  private final InMemorySnapshotSources.Holdings holdings = new InMemorySnapshotSources.Holdings();
  private final InMemorySnapshotSources.UnitRows unitRows = new InMemorySnapshotSources.UnitRows();
  private final InMemorySnapshotSources.Lots lots = new InMemorySnapshotSources.Lots();
  private final InMemorySnapshotSources.Navs navs = new InMemorySnapshotSources.Navs();
  private final InMemorySnapshotSources.Objects objects = new InMemorySnapshotSources.Objects();
  private final InMemorySnapshotSources.Runs runs = new InMemorySnapshotSources.Runs();
  private final FakeSnapshotLedger ledger = new FakeSnapshotLedger();

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final SnapshotMetrics metrics = new SnapshotMetrics(registry);

  private CloseWindow window =
      CloseWindow.always(ZONE, CALENDAR, Clock.fixed(MIDDAY, ZONE), Optional::empty);
  private RunStatus internalStatus = RunStatus.PASSED;
  private RunStatus custodianStatus = RunStatus.PASSED;

  @Test
  @DisplayName("takes the snapshot when the date has none, and clears the gauge")
  void takesADateWithNoSnapshot() {
    oneParticipantHolding(100_00000000L);

    runner().sweep();

    assertThat(objects.keys()).contains("snapshots/dt=2026-03-02/positions.ndjson.gz");
    assertThat(missing()).isZero();
    assertThat(registry.get("eod_snapshot_positions").gauge().value()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("does not re-derive a date that already has its snapshot")
  void skipsADateAlreadySnapshotted() {
    // The defect this test exists for: the sweep used to call snapshot() unconditionally. On any
    // day with activity the 10:00 pass then re-derived a file that had legitimately changed since
    // 09:00, halted on the mismatch, and raised eod_snapshot_missing on a date whose snapshot
    // existed and was fine — once an hour until cut-off, with a FAILED row each time.
    oneParticipantHolding(100_00000000L);
    runner().sweep();
    int runsAfterFirst = runs.all().size();

    // The book moves during the day, exactly as it does in production.
    holdings.with(new HoldingKey(organizationId, "P-002", fundId));
    unitsFor("P-002", 50_00000000L);
    lots.with(new HoldingKey(organizationId, "P-002", fundId), 50_00000000L, 1_00000000L, MIDDAY);
    unitsInIssue(150_00000000L);

    runner().sweep();

    assertThat(runs.all())
        .as("no second run row, and above all no FAILED one")
        .hasSize(runsAfterFirst);
    assertThat(missing()).as("the snapshot exists, so nothing is missing").isZero();
  }

  @Test
  @DisplayName("before the close window opens, the sweep takes nothing and opens no run")
  void beforeTheWindowNothingIsTaken() {
    // The demo's defect: a sweep at 09:00 took the date's one snapshot of record before the
    // day's payroll and NAV strike reached the book, and the month-end statement read that file.
    oneParticipantHolding(100_00000000L);
    window =
        CloseWindow.openingAt(
            LocalTime.of(17, 0), ZONE, CALENDAR, Clock.fixed(MIDDAY, ZONE), Optional::empty);

    runner().sweep();

    assertThat(runs.all()).isEmpty();
    assertThat(objects.keys()).isEmpty();
  }

  @Test
  @DisplayName("inside the close window, the sweep takes the snapshot")
  void insideTheWindowTheSnapshotIsTaken() {
    oneParticipantHolding(100_00000000L);
    window =
        CloseWindow.openingAt(
            LocalTime.of(11, 0), ZONE, CALENDAR, Clock.fixed(MIDDAY, ZONE), Optional::empty);

    runner().sweep();

    assertThat(objects.keys()).contains("snapshots/dt=2026-03-02/positions.ndjson.gz");
  }

  @Test
  @DisplayName("a blocked gate leaves the gauge raised and does not escape the scheduler")
  void aBlockedGateIsSwallowedAndRaisesTheGauge() {
    oneParticipantHolding(100_00000000L);
    internalStatus = RunStatus.FAILED;

    runner().sweep();

    assertThat(missing()).isEqualTo(1.0);
    assertThat(runs.all()).as("a blocked close opens no run").isEmpty();
  }

  @Test
  @DisplayName("a custodian block is swallowed the same way")
  void aCustodianBlockIsSwallowed() {
    oneParticipantHolding(100_00000000L);
    custodianStatus = RunStatus.FAILED;

    runner().sweep();

    assertThat(missing()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a tie failure counts the untied funds as well as raising the gauge — it alerts")
  void aTieFailureAlerts() {
    oneParticipantHolding(100_00000000L);
    unitsInIssue(175_00000000L);

    runner().sweep();

    assertThat(missing()).isEqualTo(1.0);
    assertThat(registry.get("eod_snapshot_untied_total").counter().count())
        .as("the counter an alert rule watches for a file that is wrong about money")
        .isEqualTo(1.0);
    assertThat(objects.keys()).isEmpty();
  }

  @Test
  @DisplayName("an object-store outage raises the gauge and never escapes")
  void anOutageIsSwallowed() {
    oneParticipantHolding(100_00000000L);
    objects.failingWith(
        new ObjectStoreUnavailableException("could not store the object", new IOException()));

    runner().sweep();

    assertThat(missing()).isEqualTo(1.0);
    assertThat(runs.all()).hasSize(1);
    assertThat(runs.all().get(0).status()).isEqualTo(SnapshotStatus.FAILED);
  }

  // ---- a date still unsigned after its cut-off -----------------------------------------------

  @Test
  @DisplayName(
      "a date unsigned at cut-off is snapshotted after 18:00, chained to the previous date's"
          + " manifest, on a falling NAV")
  void aDateUnsignedAtCutoffIsSnapshottedLate() {
    LocalDate friday = LocalDate.of(2026, 2, 27);
    HoldingKey key = new HoldingKey(organizationId, "P-001", fundId);
    holdings.with(key);
    unitsFor("P-001", 100_00000000L, friday);
    lots.with(
        key, 100_00000000L, 1_00000000L, friday.atTime(LocalTime.NOON).atZone(ZONE).toInstant());
    navs.with(fundId, 1L, 1_00000000L, friday);
    unitsInIssue(100_00000000L);
    Clock fridayClose = Clock.fixed(friday.atTime(17, 30).atZone(ZONE).toInstant(), ZONE);
    window =
        CloseWindow.openingAt(LocalTime.of(17, 0), ZONE, CALENDAR, fridayClose, Optional::empty);
    runner(friday, fridayClose).sweep();
    SnapshotRun fridayRun = runs.all().get(0);

    // Friday signed off. Monday's NAV falls to 0.92, and Monday's reconciliation only ties at
    // 18:03.
    navs.with(fundId, 2L, 92_000000L, TODAY);
    Clock afterCutoff = Clock.fixed(TODAY.atTime(18, 3).atZone(ZONE).toInstant(), ZONE);
    window =
        CloseWindow.openingAt(
            LocalTime.of(17, 0), ZONE, CALENDAR, afterCutoff, () -> Optional.of(friday));

    runner(TODAY, afterCutoff).sweep();

    assertThat(objects.keys())
        .contains("snapshots/dt=2026-03-02/positions.ndjson.gz")
        .noneMatch(objectKey -> objectKey.contains("dt=2026-03-03"));
    SnapshotRun mondayRun = runs.all().get(runs.all().size() - 1);
    assertThat(mondayRun.businessDate()).isEqualTo(TODAY);
    assertThat(mondayRun.status()).isEqualTo(SnapshotStatus.COMPLETED);
    assertThat(mondayRun.previousManifestSha256())
        .as("Monday links to Friday's manifest, so the chain stays linear")
        .isEqualTo(fridayRun.manifestSha256());
    String line = linesOf("snapshots/dt=2026-03-02/positions.ndjson.gz").get(0);
    assertThat(line).contains("\"valueMinorUnits\":9200");
    assertThat(line)
        .as("the loss is signed and unclamped")
        .contains("\"unrealisedGainMinorUnits\":-800");
    assertThat(missing()).isZero();
  }

  @Test
  @DisplayName("the next date's snapshot is not taken while an earlier date is unsigned")
  void theNextDateWaitsBehindAnUnsignedOne() {
    LocalDate friday = LocalDate.of(2026, 2, 27);
    LocalDate tuesday = TODAY.plusDays(1);
    oneParticipantHolding(100_00000000L);
    Clock tuesdayClose = Clock.fixed(tuesday.atTime(17, 30).atZone(ZONE).toInstant(), ZONE);
    window =
        CloseWindow.openingAt(
            LocalTime.of(17, 0), ZONE, CALENDAR, tuesdayClose, () -> Optional.of(friday));

    // Tuesday's gates are open; Monday's never were.
    runner(tuesday, tuesdayClose).sweep();

    assertThat(runs.all()).isEmpty();
    assertThat(objects.keys()).isEmpty();
    assertThat(missing()).isEqualTo(1.0);
  }

  // ---- fixture -------------------------------------------------------------------------------

  private List<String> linesOf(String objectKey) {
    try (GZIPInputStream in =
        new GZIPInputStream(new ByteArrayInputStream(objects.stored().get(objectKey)))) {
      return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private double missing() {
    return registry.get("eod_snapshot_missing").gauge().value();
  }

  private SnapshotRunner runner() {
    return runner(TODAY, Clock.fixed(MIDDAY, ZONE));
  }

  /** A runner whose reconciliation gates have passed for {@code gatedDate} only. */
  private SnapshotRunner runner(LocalDate gatedDate, Clock clock) {
    PositionFileBuilder builder =
        new PositionFileBuilder(
            accountIds, tenantRefs, holdings, unitRows, lots, navs, PROPERTIES, CALENDAR);
    SnapshotService service =
        new SnapshotService(
            ledger,
            accountIds,
            funds,
            builder,
            objects,
            runs,
            new ReconciliationGate(
                InMemorySnapshotSources.Gates.internal(gatedDate, internalStatus)),
            new CustodianReconciliationGate(
                InMemorySnapshotSources.Gates.custodian(gatedDate, custodianStatus)),
            PROPERTIES,
            clock);
    return new SnapshotRunner(service, metrics, window);
  }

  private void oneParticipantHolding(long unitsScaled) {
    HoldingKey key = new HoldingKey(organizationId, "P-001", fundId);
    holdings.with(key);
    unitsFor("P-001", unitsScaled);
    lots.with(key, unitsScaled, 1_00000000L, MIDDAY);
    navs.with(fundId, 1L, 1_00000000L, TODAY);
    unitsInIssue(unitsScaled);
  }

  private void unitsFor(String participantRef, long unitsScaled) {
    unitsFor(participantRef, unitsScaled, TODAY);
  }

  private void unitsFor(String participantRef, long unitsScaled, LocalDate effectiveDate) {
    HoldingKey key = new HoldingKey(organizationId, participantRef, fundId);
    unitRows.with(
        tenantRefs.derive(organizationId).asUnsignedDecimal(),
        accountIds
            .derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, key.scope())
            .asUnsignedDecimal(),
        effectiveDate,
        unitsScaled);
  }

  private void unitsInIssue(long unitsScaled) {
    ledger.withUnitsInIssue(
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.value().toString()),
        unitLedgerId,
        unitsScaled);
  }
}
