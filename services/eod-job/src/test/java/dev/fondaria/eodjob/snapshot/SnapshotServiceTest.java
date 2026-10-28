package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.config.SnapshotProperties;
import dev.fondaria.eodjob.custodian.CustodianCloseBlockedException;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.reconciliation.CloseBlockedException;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Task 7.4's acceptance criteria, against in-memory sources.
 *
 * <p>The pass itself is entirely real here — the keyset scan's paging, the as-of read of a sparse
 * holding table, the per-lot cost basis and its posting-date bound, the NDJSON rendering, the gzip,
 * the digest, the per-fund tie, the re-run check and the manifest chain. What is faked is only
 * where the rows come from, which is what lets a single test say precisely what the file should
 * contain and then assert every byte of it.
 *
 * <p>Falling and volatile NAV series are exercised deliberately, not incidentally:
 * docs/ENGINEERING-RULES.md §4 requires it, and unrealised gain is the field that is wrong in
 * exactly one direction if a suite only ever prices upward.
 */
class SnapshotServiceTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(ZONE, LocalTime.of(18, 0), Set.of());

  private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
  private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 3, 4);

  /** A page size of two, so every multi-participant test genuinely crosses a page boundary. */
  private static final SnapshotProperties PROPERTIES =
      new SnapshotProperties(Duration.ofHours(1), 2, 8_189, "snapshots", 64L * 1024 * 1024);

  private final UUID namespace = UUID.randomUUID();
  private final AccountIdDeriver accountIds = new AccountIdDeriver(namespace);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(namespace);

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = new FundId(UUID.randomUUID());
  private final long unitLedgerId = 1001L;
  private final String tenantId = tenantRefs.derive(organizationId).asUnsignedDecimal();

  /** Midday local, comfortably inside {@code date} either side of the 18:00 cut-off. */
  private static Instant middayOf(LocalDate date) {
    return date.atTime(LocalTime.NOON).atZone(ZONE).toInstant();
  }

  // ---- the file ------------------------------------------------------------------------------

  @Nested
  @DisplayName("the file")
  class TheFile {

    @Test
    @DisplayName("holds one row per open position, and its count is the run's own record count")
    void oneRowPerOpenPosition() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 500_00000000L, MONDAY);
      fixture.holdingWith("P-002", 250_00000000L, MONDAY);
      fixture.holdingWith("P-003", 250_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(1000_00000000L);

      SnapshotResult result = fixture.snapshot(MONDAY);

      assertThat(result.positionCount()).isEqualTo(3L);
      assertThat(fixture.linesOfStoredFile()).hasSize(3);
      assertThat(result.run().positionCount()).isEqualTo(3L);
      assertThat(result.ties()).isTrue();
    }

    @Test
    @DisplayName("counts a holding's vested and unvested accounts as one position")
    void vestedAndUnvestedAreOnePosition() {
      Fixture fixture = new Fixture();
      HoldingKey holding = fixture.holding("P-001");
      fixture.units(
          holding, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, 30_00000000L, MONDAY);
      fixture.units(
          holding, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, 70_00000000L, MONDAY);
      fixture.lot(holding, 100_00000000L, 1_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      SnapshotResult result = fixture.snapshot(MONDAY);

      assertThat(result.positionCount())
          .as("one participant, one fund, two ledger accounts — one row")
          .isEqualTo(1L);
      assertThat(fixture.linesOfStoredFile().get(0))
          .as("vested plus unvested, or a VESTING transfer would show up as a change in holding")
          .contains("\"unitsScaled\":10000000000");
    }

    @Test
    @DisplayName("omits a holding that holds nothing — it is not an open position")
    void closedPositionsAreOmitted() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      // Allotted once and fully redeemed: the projection's latest row is zero.
      HoldingKey closed = fixture.holding("P-002");
      fixture.units(closed, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, 0L, MONDAY);
      fixture.lot(closed, 50_00000000L, 1_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      SnapshotResult result = fixture.snapshot(MONDAY);

      assertThat(result.positionCount()).isEqualTo(1L);
      assertThat(fixture.linesOfStoredFile().get(0)).contains("P-001").doesNotContain("P-002");
    }

    @Test
    @DisplayName("is stored partitioned by date, beside its manifest")
    void storedPartitionedByDate() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      fixture.snapshot(MONDAY);

      assertThat(fixture.objects.keys())
          .containsExactly(
              "snapshots/dt=2026-03-02/positions.ndjson.gz",
              "snapshots/dt=2026-03-02/manifest.json");
    }
  }

  // ---- density -------------------------------------------------------------------------------

  @Nested
  @DisplayName("density")
  class Density {

    @Test
    @DisplayName("a dormant participant has a row every day, from the row they last moved on")
    void dormantParticipantAppearsEveryDay() {
      Fixture fixture = new Fixture();
      // One movement, on Monday, and nothing since. projection.participant_units is sparse: there
      // is no Tuesday row and no Wednesday row to read.
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.navOf(2L, 1_10000000L, TUESDAY);
      fixture.navOf(3L, 90000000L, WEDNESDAY);
      fixture.unitsInIssue(100_00000000L);

      for (LocalDate date : List.of(MONDAY, TUESDAY, WEDNESDAY)) {
        assertThat(fixture.snapshot(date).positionCount())
            .as("the participant did not move on " + date + ", and still holds units")
            .isEqualTo(1L);
      }

      // And each day valued at that day's price, from the same untouched holding.
      assertThat(fixture.linesOf("snapshots/dt=2026-03-02/positions.ndjson.gz").get(0))
          .contains("\"valueMinorUnits\":10000");
      assertThat(fixture.linesOf("snapshots/dt=2026-03-03/positions.ndjson.gz").get(0))
          .contains("\"valueMinorUnits\":11000");
      assertThat(fixture.linesOf("snapshots/dt=2026-03-04/positions.ndjson.gz").get(0))
          .contains("\"valueMinorUnits\":9000");
    }

    @Test
    @DisplayName("record count equals open positions on every one of ninety consecutive dates")
    void ninetyConsecutiveDates() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 60_00000000L, MONDAY);
      fixture.holdingWith("P-002", 40_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);
      // A volatile series across the whole run — rising, falling and flat — so a suite that only
      // ever priced upward cannot pass here (docs/ENGINEERING-RULES.md §4).
      for (int day = 0; day < 90; day++) {
        fixture.navOf(day + 1L, 1_00000000L + (day % 7 - 3) * 10_000_000L, MONDAY.plusDays(day));
      }

      String previousManifest = null;
      for (int day = 0; day < 90; day++) {
        LocalDate date = MONDAY.plusDays(day);
        SnapshotResult result = fixture.snapshot(date);

        assertThat(result.positionCount()).as("open positions on " + date).isEqualTo(2L);
        assertThat(result.ties()).isTrue();
        assertThat(result.manifest().orElseThrow().previousManifestSha256().orElse(null))
            .as("every date chains to the one before it, all ninety of them")
            .isEqualTo(previousManifest);
        previousManifest = result.run().manifestSha256().orElseThrow();
      }
      assertThat(fixture.objects.keys()).hasSize(180);
    }
  }

  // ---- valuation -----------------------------------------------------------------------------

  @Nested
  @DisplayName("valuation")
  class Valuation {

    @Test
    @DisplayName("unrealised gain goes negative on a falling NAV, and is never clamped")
    void unrealisedGainIsSigned() {
      Fixture fixture = new Fixture();
      HoldingKey holding = fixture.holding("P-001");
      fixture.units(
          holding, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, 100_00000000L, MONDAY);
      // Bought at 1.00; the price falls to 0.75.
      fixture.lot(holding, 100_00000000L, 1_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.navOf(2L, 75000000L, TUESDAY);
      fixture.unitsInIssue(100_00000000L);

      fixture.snapshot(TUESDAY);

      String line = fixture.linesOf("snapshots/dt=2026-03-03/positions.ndjson.gz").get(0);
      assertThat(line).contains("\"valueMinorUnits\":7500");
      assertThat(line).contains("\"costBasisMinorUnits\":10000");
      assertThat(line)
          .as(
              "values go down, and a loss is a number rather than a floor (docs/ENGINEERING-RULES.md §4)")
          .contains("\"unrealisedGainMinorUnits\":-2500");
    }

    @Test
    @DisplayName("cost basis is each lot at its own price, across a volatile series")
    void costBasisIsPerLot() {
      Fixture fixture = new Fixture();
      HoldingKey holding = fixture.holding("P-001");
      // Three purchases: up, down, up again. 100 units at 1.00, 100 at 0.50, 100 at 1.25.
      fixture.lot(holding, 100_00000000L, 1_00000000L, MONDAY);
      fixture.lot(holding, 100_00000000L, 50000000L, TUESDAY);
      fixture.lot(holding, 100_00000000L, 1_25000000L, WEDNESDAY);
      fixture.units(
          holding, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, 300_00000000L, WEDNESDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.navOf(2L, 50000000L, TUESDAY);
      fixture.navOf(3L, 1_25000000L, WEDNESDAY);
      fixture.unitsInIssue(300_00000000L);

      fixture.snapshot(WEDNESDAY);

      String line = fixture.linesOf("snapshots/dt=2026-03-04/positions.ndjson.gz").get(0);
      // 10000 + 5000 + 12500 cents paid; 300 units at 1.25 is 37500 cents today.
      assertThat(line).contains("\"costBasisMinorUnits\":27500");
      assertThat(line).contains("\"valueMinorUnits\":37500");
      assertThat(line).contains("\"unrealisedGainMinorUnits\":10000");
    }

    @Test
    @DisplayName("a lot dealt on the date but posted after it contributes no cost to that date")
    void aLotDealtBeforeButPostedAfterIsNotInTheBasis() {
      // The regression this test exists for: allotment prices a batch at the NAV struck for its
      // DEALING date, while projection.participant_units files the units under the business date
      // of the LEDGER COMMIT. Bounding lots on the NAV's valuation date therefore put Monday's lot
      // into Monday's basis while its units, posted Tuesday, were not there — a fabricated loss of
      // the whole lot's value on a position that had not moved.
      Fixture fixture = new Fixture();
      HoldingKey holding = fixture.holding("P-001");
      fixture.units(
          holding, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, 100_00000000L, MONDAY);
      fixture.lot(holding, 100_00000000L, 1_00000000L, MONDAY);
      // Dealt at Monday's NAV, posted Tuesday — allotment-job was behind.
      fixture.lots.with(holding, 50_00000000L, 1_00000000L, middayOf(TUESDAY));
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      fixture.snapshot(MONDAY);

      assertThat(fixture.linesOf("snapshots/dt=2026-03-02/positions.ndjson.gz").get(0))
          .as("Tuesday's posting has no units behind it on Monday, so it must not be paid for")
          .contains("\"costBasisMinorUnits\":10000");
      assertThat(fixture.linesOf("snapshots/dt=2026-03-02/positions.ndjson.gz").get(0))
          .as("and the position shows no gain or loss it did not have")
          .contains("\"unrealisedGainMinorUnits\":0");
    }

    @Test
    @DisplayName("a fund holding positions with no price on or before the date fails the pass")
    void anUnpricedFundFailsRatherThanValuingAtZero() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, WEDNESDAY);
      fixture.unitsInIssue(100_00000000L);

      assertThatExceptionOfType(SnapshotFailedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY))
          .withMessageContaining("no NAV struck on or before this date");
      assertThat(fixture.objects.keys()).isEmpty();
      assertThat(fixture.lastRun().status()).isEqualTo(SnapshotStatus.FAILED);
    }
  }

  // ---- the tie -------------------------------------------------------------------------------

  @Nested
  @DisplayName("the tie to units in issue")
  class TheTie {

    @Test
    @DisplayName("a missing position breaks the tie, fails the pass, and stores nothing")
    void aMissingPositionBreaksTheTie() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      // The ledger issued 150 units; the projection only ever recorded 100 of them.
      fixture.unitsInIssue(150_00000000L);

      Throwable thrown = catchThrowable(() -> fixture.snapshot(MONDAY));
      assertThat(thrown).isInstanceOf(SnapshotNotTiedException.class);
      SnapshotNotTiedException failure = (SnapshotNotTiedException) thrown;

      assertThat(failure.untiedFunds()).hasSize(1);
      assertThat(failure.untiedFunds().get(0).driftScaled())
          .as("signed: the file holds less than the ledger issued")
          .isEqualTo(-50_00000000L);
      assertThat(fixture.objects.keys())
          .as("a snapshot that does not tie is not a snapshot, and is never stored")
          .isEmpty();
      assertThat(fixture.lastRun().status()).isEqualTo(SnapshotStatus.FAILED);
      assertThat(fixture.lastRun().failureReason().orElseThrow())
          .contains("did not tie to units in issue");
    }

    @Test
    @DisplayName("a fund whose every position vanished is still checked, not skipped")
    void aFundWithNoRowsIsStillChecked() {
      Fixture fixture = new Fixture();
      // No holdings at all, but the ledger says units are in issue.
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      assertThatExceptionOfType(SnapshotNotTiedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY))
          .withMessageContaining("did not tie");
    }

    @Test
    @DisplayName("an empty fund with nothing in issue ties, and appears in the manifest")
    void anEmptyFundTies() {
      Fixture fixture = new Fixture();
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(0L);

      SnapshotResult result = fixture.snapshot(MONDAY);

      assertThat(result.positionCount()).isZero();
      assertThat(result.fundTotals()).hasSize(1);
      assertThat(result.fundTotals().get(0).ties()).isTrue();
    }
  }

  // ---- re-runs -------------------------------------------------------------------------------

  @Nested
  @DisplayName("re-running a date")
  class ReRunning {

    @Test
    @DisplayName("reproduces the identical hash, stores nothing, and records no second run")
    void anIdenticalRerunRecordsNothing() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.holdingWith("P-002", 300_00000000L, MONDAY);
      fixture.navOf(1L, 1_07000000L, MONDAY);
      fixture.unitsInIssue(400_00000000L);

      SnapshotResult first = fixture.snapshot(MONDAY);
      byte[] storedFirst =
          fixture.objects.stored().get("snapshots/dt=2026-03-02/positions.ndjson.gz");
      SnapshotResult second = fixture.snapshot(MONDAY);

      assertThat(second.run().runId())
          .as("a verification is not a snapshot: the run of record is still the first one")
          .isEqualTo(first.run().runId());
      assertThat(second.newlyStored()).isFalse();
      assertThat(second.manifest()).isEmpty();
      assertThat(fixture.runs.all()).as("and no second row was written for the date").hasSize(1);
      assertThat(fixture.objects.stored().get("snapshots/dt=2026-03-02/positions.ndjson.gz"))
          .as("the stored object is the one taken first, untouched")
          .isSameAs(storedFirst);
    }

    @Test
    @DisplayName("halts when the book has moved, records why, and never overwrites the file")
    void aDifferingRerunHalts() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      SnapshotResult first = fixture.snapshot(MONDAY);
      byte[] storedFirst =
          fixture.objects.stored().get("snapshots/dt=2026-03-02/positions.ndjson.gz");

      // A late allotment lands, posted and projected into Monday's close.
      fixture.holdingWith("P-002", 50_00000000L, MONDAY);
      fixture.unitsInIssue(150_00000000L);

      Throwable thrown = catchThrowable(() -> fixture.snapshot(MONDAY));
      assertThat(thrown).isInstanceOf(SnapshotMismatchException.class);
      SnapshotMismatchException failure = (SnapshotMismatchException) thrown;

      assertThat(failure.establishedSha256())
          .isEqualTo(first.run().positionsSha256().orElseThrow());
      assertThat(failure.recomputedSha256()).isNotEqualTo(failure.establishedSha256());
      assertThat(fixture.objects.stored().get("snapshots/dt=2026-03-02/positions.ndjson.gz"))
          .as("never silently overwrites")
          .isSameAs(storedFirst);
      assertThat(fixture.lastRun().status())
          .as("a disagreement about a published snapshot is a finding, not a log line")
          .isEqualTo(SnapshotStatus.FAILED);
      assertThat(fixture.lastRun().failureReason().orElseThrow())
          .contains("a snapshot already exists for this date");
    }

    @Test
    @DisplayName("a verification whose book no longer ties records the finding")
    void aVerificationThatNoLongerTiesIsRecorded() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);
      fixture.snapshot(MONDAY);

      // The file is unchanged, but units were issued that the projection has not recorded.
      fixture.unitsInIssue(175_00000000L);

      assertThatExceptionOfType(SnapshotNotTiedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));
      assertThat(fixture.lastRun().status()).isEqualTo(SnapshotStatus.FAILED);
      assertThat(fixture.lastRun().failureReason().orElseThrow())
          .contains("did not tie to units in issue");
    }

    @Test
    @DisplayName("chains each manifest to the previous business date's")
    void manifestsChain() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.navOf(2L, 1_02000000L, TUESDAY);
      fixture.unitsInIssue(100_00000000L);

      SnapshotResult monday = fixture.snapshot(MONDAY);
      SnapshotResult tuesday = fixture.snapshot(TUESDAY);

      assertThat(monday.manifest().orElseThrow().previousManifestSha256())
          .as("the genesis of the chain is the only legitimate absence")
          .isEmpty();
      assertThat(tuesday.manifest().orElseThrow().previousManifestSha256())
          .contains(monday.run().manifestSha256().orElseThrow());
    }
  }

  // ---- the gates -----------------------------------------------------------------------------

  @Nested
  @DisplayName("the upstream gates")
  class Gates {

    @Test
    @DisplayName("internal reconciliation drift blocks the snapshot before a run is opened")
    void internalDriftBlocks() {
      Fixture fixture = new Fixture().withInternalStatus(RunStatus.FAILED);
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      assertThatExceptionOfType(CloseBlockedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));
      assertThat(fixture.runs.all())
          .as("a blocked close has not failed to snapshot; it was not allowed to start")
          .isEmpty();
      assertThat(fixture.objects.keys()).isEmpty();
    }

    @Test
    @DisplayName("a custodian break blocks the snapshot too")
    void aCustodianBreakBlocks() {
      Fixture fixture = new Fixture().withCustodianStatus(RunStatus.FAILED);
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);

      assertThatExceptionOfType(CustodianCloseBlockedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));
      assertThat(fixture.runs.all()).isEmpty();
    }

    @Test
    @DisplayName("an unfinished reconciliation blocks — the gates fail closed")
    void anUnfinishedReconciliationBlocks() {
      Fixture fixture = new Fixture().withInternalStatus(RunStatus.RUNNING);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(0L);

      assertThatExceptionOfType(CloseBlockedException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));
    }
  }

  // ---- failures ------------------------------------------------------------------------------

  @Nested
  @DisplayName("when something goes wrong")
  class Failures {

    @Test
    @DisplayName("an object-store outage fails the run, and the re-run stores the same file")
    void anObjectStoreOutageIsRecoverable() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);
      fixture.objects.failingWith(
          new ObjectStoreUnavailableException("could not store the object", new IOException()));

      assertThatExceptionOfType(ObjectStoreUnavailableException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));
      assertThat(fixture.lastRun().status()).isEqualTo(SnapshotStatus.FAILED);
      assertThat(fixture.lastRun().failureReason()).contains("the pass did not complete");

      fixture.objects.failingWith(null);
      assertThatCode(() -> fixture.snapshot(MONDAY)).doesNotThrowAnyException();
      assertThat(fixture.objects.keys()).contains("snapshots/dt=2026-03-02/positions.ndjson.gz");
    }

    @Test
    @DisplayName("records what it is about to store before the bytes leave")
    void intentIsRecordedBeforeStoring() {
      Fixture fixture = new Fixture();
      fixture.holdingWith("P-001", 100_00000000L, MONDAY);
      fixture.navOf(1L, 1_00000000L, MONDAY);
      fixture.unitsInIssue(100_00000000L);
      fixture.objects.failingWith(
          new ObjectStoreUnavailableException("could not store the object", new IOException()));

      assertThatExceptionOfType(ObjectStoreUnavailableException.class)
          .isThrownBy(() -> fixture.snapshot(MONDAY));

      assertThat(fixture.runs.intents())
          .as("a pass that died at the store still says what it meant to put there")
          .hasSize(1);
    }
  }

  // ---- fixture -------------------------------------------------------------------------------

  /** Assembles the service over in-memory sources, and reads back what it stored. */
  private final class Fixture {

    private final InMemorySnapshotSources.Funds funds =
        new InMemorySnapshotSources.Funds().with(fundId, unitLedgerId);
    private final InMemorySnapshotSources.Holdings holdings =
        new InMemorySnapshotSources.Holdings();
    private final InMemorySnapshotSources.UnitRows unitRows =
        new InMemorySnapshotSources.UnitRows();
    private final InMemorySnapshotSources.Navs navs = new InMemorySnapshotSources.Navs();
    private final FakeSnapshotLedger ledger = new FakeSnapshotLedger();
    private final Set<HoldingKey> registered = new LinkedHashSet<>();

    final InMemorySnapshotSources.Lots lots = new InMemorySnapshotSources.Lots();
    final InMemorySnapshotSources.Objects objects = new InMemorySnapshotSources.Objects();
    final InMemorySnapshotSources.Runs runs = new InMemorySnapshotSources.Runs();

    private RunStatus internalStatus = RunStatus.PASSED;
    private RunStatus custodianStatus = RunStatus.PASSED;

    Fixture withInternalStatus(RunStatus status) {
      this.internalStatus = status;
      return this;
    }

    Fixture withCustodianStatus(RunStatus status) {
      this.custodianStatus = status;
      return this;
    }

    HoldingKey holding(String participantInternalRef) {
      HoldingKey key = new HoldingKey(organizationId, participantInternalRef, fundId);
      if (registered.add(key)) {
        holdings.with(key);
      }
      return key;
    }

    /** A participant allotted once, at 1.00, holding it all vested. */
    void holdingWith(String participantInternalRef, long unitsScaled, LocalDate on) {
      HoldingKey key = holding(participantInternalRef);
      units(key, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, unitsScaled, on);
      lot(key, unitsScaled, 1_00000000L, on);
    }

    void units(
        HoldingKey key, LedgerCodes.AccountCode code, long unitsScaled, LocalDate effectiveDate) {
      unitRows.with(
          tenantId,
          accountIds.derive(code, key.scope()).asUnsignedDecimal(),
          effectiveDate,
          unitsScaled);
    }

    /** A lot posted midday on {@code postedOn} — the date the projection files its units under. */
    void lot(HoldingKey key, long unitsScaled, long navScaled, LocalDate postedOn) {
      lots.with(key, unitsScaled, navScaled, middayOf(postedOn));
    }

    void navOf(long navVersionId, long navScaled, LocalDate valuationDate) {
      navs.with(fundId, navVersionId, navScaled, valuationDate);
    }

    void unitsInIssue(long unitsScaled) {
      ledger.withUnitsInIssue(
          accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.value().toString()),
          unitLedgerId,
          unitsScaled);
    }

    SnapshotResult snapshot(LocalDate businessDate) {
      Instant at = middayOf(businessDate);
      // Fixed at the same instant, so a pass's recorded duration is zero rather than the months
      // between a fixture date and the wall clock — and so nothing here reads the system clock.
      return service(businessDate, Clock.fixed(at, ZONE)).snapshotFor(businessDate, at);
    }

    private SnapshotService service(LocalDate gatedDate, Clock clock) {
      PositionFileBuilder builder =
          new PositionFileBuilder(
              accountIds, tenantRefs, holdings, unitRows, lots, navs, PROPERTIES, CALENDAR);
      return new SnapshotService(
          ledger,
          accountIds,
          funds,
          builder,
          objects,
          runs,
          new ReconciliationGate(InMemorySnapshotSources.Gates.internal(gatedDate, internalStatus)),
          new CustodianReconciliationGate(
              InMemorySnapshotSources.Gates.custodian(gatedDate, custodianStatus)),
          PROPERTIES,
          clock);
    }

    SnapshotRun lastRun() {
      List<SnapshotRun> all = runs.all();
      return all.get(all.size() - 1);
    }

    List<String> linesOfStoredFile() {
      return linesOf(objects.keys().get(0));
    }

    List<String> linesOf(String objectKey) {
      byte[] gzipped = objects.stored().get(objectKey);
      assertThat(gzipped).as("no object stored under " + objectKey).isNotNull();
      try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
        String ndjson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return ndjson.isEmpty() ? List.of() : List.of(ndjson.split("\n"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
