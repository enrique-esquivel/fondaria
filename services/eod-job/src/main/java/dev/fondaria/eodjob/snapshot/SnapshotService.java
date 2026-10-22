package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.SnapshotProperties;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.fund.FundDirectory;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The dense daily snapshot (phase-07 Task 7.4): every open participant position, for a business
 * date, valued and written to object storage with a manifest that proves the file complete.
 *
 * <p>This class is the orchestration — the gates, the tie, and what is stored or refused. {@link
 * PositionFileBuilder} is the read, and holds the reasoning about consistency and density.
 *
 * <h2>Once per date, and the second pass is a verification</h2>
 *
 * <p>A date has at most one snapshot of record ({@code snapshot_run_one_of_record_per_date}, V28).
 * The first pass for a date takes it. A later pass <b>re-derives the file and compares</b>: equal,
 * and it records nothing, because a verification is not a snapshot; different, and it halts and
 * records a {@code FAILED} run naming both hashes. Nothing overwrites the stored object either way.
 *
 * <p>That is why {@code SnapshotRunner} does not simply call this every hour. A book still moving
 * during the day legitimately produces a different file each pass, and a scheduler that re-derived
 * hourly would spend the day halting and raising alerts about a snapshot that exists and is fine.
 *
 * <h2>The one check that proves the file, and what it does not prove</h2>
 *
 * <p>{@code Σ units} in the file must equal {@code units_in_issue} per fund, and {@code
 * units_in_issue} is read from {@code LedgerPort} — never summed from PostgreSQL (ADR-0008). Every
 * registered fund is checked, not only the funds the file holds rows for: a fund whose every
 * position vanished would otherwise pass by being absent from both sides at once. A fund that does
 * not tie fails the pass and <b>nothing is stored</b>.
 *
 * <p><b>It is a sum, so it cannot see units moved between holdings.</b> If a CDC replay wrote
 * participant P's units under participant Q's account, the fund's total is unchanged and this
 * passes — P silently drops out of the file and Q's row is too large. Catching that needs a
 * per-holding control, and the platform's is {@code projection.participant_units.row_hash}, the
 * chain Task 5.5 writes. Stated here because the check is easy to over-read: it proves units are
 * neither lost nor invented, not that each is against the right name.
 *
 * <h2>Live ledger against an as-of projection</h2>
 *
 * <p>The file is as at {@code businessDate}; {@code units_in_issue} is whatever the ledger holds
 * now, because {@code LedgerPort} has no as-of read. It is deliberately read <b>before</b> the file
 * is built rather than after, so an issuance landing during the pass makes the file look larger
 * than issue rather than smaller — "the ledger has not caught up with what I read" rather than "a
 * position is missing", which is the difference between a message that points at the cause and one
 * that points away from it. Either way it is drift, and the correct response is to run the pass
 * after cut-off, when the book has stopped moving.
 *
 * <h2>This class never writes to the ledger</h2>
 *
 * <p>It cannot: {@code LedgerConfiguration} wires no {@code TransferIdDeriver}. It reads {@code
 * UNITS_IN_ISSUE} and nothing else, and the id it reads is re-derived from the fund rather than
 * looked up anywhere.
 */
@Service
public class SnapshotService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotService.class);

  private static final String POSITIONS_CONTENT_TYPE = "application/x-ndjson";
  private static final String MANIFEST_CONTENT_TYPE = "application/json";

  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final FundDirectory funds;
  private final PositionFileBuilder positions;
  private final SnapshotObjectStore objects;
  private final SnapshotRepository runs;
  private final ReconciliationGate internalGate;
  private final CustodianReconciliationGate custodianGate;
  private final SnapshotProperties properties;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public SnapshotService(
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      FundDirectory funds,
      PositionFileBuilder positions,
      SnapshotObjectStore objects,
      SnapshotRepository runs,
      ReconciliationGate internalGate,
      CustodianReconciliationGate custodianGate,
      SnapshotProperties properties,
      BusinessCalendar calendar,
      Clock clock) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.positions = Objects.requireNonNull(positions, "positions must not be null");
    this.objects = Objects.requireNonNull(objects, "objects must not be null");
    this.runs = Objects.requireNonNull(runs, "runs must not be null");
    this.internalGate = Objects.requireNonNull(internalGate, "internalGate must not be null");
    this.custodianGate = Objects.requireNonNull(custodianGate, "custodianGate must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /** Takes the snapshot for the business date the clock currently falls in. */
  public SnapshotResult snapshot() {
    Instant startedAt = clock.instant();
    return snapshotFor(calendar.businessDateOf(startedAt), startedAt);
  }

  /** The business date the clock currently falls in. */
  public LocalDate businessDateNow() {
    return calendar.businessDateOf(clock.instant());
  }

  /** Whether a date already has its snapshot of record. */
  public boolean isTaken(LocalDate businessDate) {
    return runs.latestCompletedFor(businessDate).isPresent();
  }

  /**
   * Takes the snapshot for one business date, or verifies the one already taken.
   *
   * <p><b>Both gates are checked first, before any run row exists.</b> A close blocked by drift or
   * by a custodian break has not failed to snapshot — it has not been allowed to start, and
   * recording it as a failed pass would put the wrong story in the audit trail and bury the actual
   * finding. Internal reconciliation (7.2) proves the platform agrees with itself; custodian
   * reconciliation (7.3) proves it agrees with the counterparty. A file written past either is a
   * file statements could be rendered from.
   */
  public SnapshotResult snapshotFor(LocalDate businessDate, Instant startedAt) {
    internalGate.requirePassed(businessDate);
    custodianGate.requirePassed(businessDate);

    Optional<SnapshotRun> established = runs.latestCompletedFor(businessDate);
    return established.isPresent()
        ? verifyAgainst(established.get(), businessDate, startedAt)
        : take(businessDate, startedAt);
  }

  // ---- taking one ---------------------------------------------------------------------------

  private SnapshotResult take(LocalDate businessDate, Instant startedAt) {
    UUID runId = UUID.randomUUID();
    // Opened before anything is read, so a process that dies mid-pass leaves a row that is visibly
    // not a snapshot rather than no row at all. Nothing downstream can tell "never ran" from
    // "crashed" — both are simply not a snapshot — but an operator can.
    runs.open(runId, businessDate, startedAt);
    try {
      List<RegisteredFund> registeredFunds = funds.allFunds();
      Map<Long, Units> unitsInIssue = readUnitsInIssue(registeredFunds);
      BuiltPositions built = positions.build(businessDate, ledgerIdByFund(registeredFunds));
      List<SnapshotFundTotal> fundTotals =
          tieAgainstLedger(businessDate, registeredFunds, built, unitsInIssue);
      return store(runId, businessDate, startedAt, built.file(), fundTotals);
    } catch (SnapshotFailedException e) {
      settleFailed(runId, businessDate, startedAt, e.reason());
      throw e;
    } catch (RuntimeException e) {
      // The reason an operator reads carries no exception text (AGENTS.md §7); the stack goes to
      // the log, which is not the audit trail.
      settleFailed(runId, businessDate, startedAt, "the pass did not complete");
      LOGGER.error("snapshot failed to complete: runId={} businessDate={}", runId, businessDate, e);
      throw e;
    }
  }

  private SnapshotResult store(
      UUID runId,
      LocalDate businessDate,
      Instant startedAt,
      PositionFile file,
      List<SnapshotFundTotal> fundTotals) {
    String positionsKey = positionsKeyFor(businessDate);
    String manifestKey = manifestKeyFor(businessDate);

    // The hash lands on the RUNNING row before the bytes leave, so a pass that dies between the two
    // leaves behind what it intended to store. An object under a date with no COMPLETED run is not
    // a snapshot of record — nothing references it — and the next pass replacing it is how a failed
    // pass recovers; this row is what lets an operator see that it happened.
    runs.recordIntent(runId, positionsKey, file.contentSha256());

    SnapshotManifest manifest =
        new SnapshotManifest(
            businessDate,
            file.positionCount(),
            positionsKey,
            file.contentSha256(),
            fundTotals,
            runs.latestCompletedBefore(businessDate).flatMap(SnapshotRun::manifestSha256));
    RenderedObject rendered = manifest.render();

    // Positions first, manifest second. The manifest names the file's hash, so a crash between the
    // two leaves an object no manifest points at — inert, and rewritten byte for byte by the
    // re-run. The opposite order would leave a manifest asserting a file that is not there.
    objects.put(positionsKey, file.gzippedBytes(), POSITIONS_CONTENT_TYPE);
    objects.put(manifestKey, rendered.bytes(), MANIFEST_CONTENT_TYPE);

    Instant completedAt = clock.instant();
    SnapshotRun run =
        new SnapshotRun(
            runId,
            businessDate,
            SnapshotStatus.COMPLETED,
            startedAt,
            Optional.of(completedAt),
            Optional.of(Duration.between(startedAt, completedAt).toMillis()),
            file.positionCount(),
            fundTotals.size(),
            Optional.of(positionsKey),
            Optional.of(file.contentSha256()),
            Optional.of(manifestKey),
            Optional.of(rendered.sha256()),
            manifest.previousManifestSha256(),
            Optional.empty());
    // If a second instance settled this date while this pass was building, the partial unique index
    // refuses this write rather than letting the later-settling run become the record while the
    // other's bytes sit in the bucket.
    runs.complete(run, fundTotals);

    LOGGER.info(
        "snapshot stored: runId={} businessDate={} positions={} funds={} compressedBytes={}"
            + " durationMillis={}",
        runId,
        businessDate,
        file.positionCount(),
        fundTotals.size(),
        file.gzippedBytes().length,
        run.durationMillis().orElseThrow());
    return new SnapshotResult(run, fundTotals, Optional.of(manifest), true);
  }

  // ---- verifying one already taken ------------------------------------------------------------

  /**
   * Re-derives the file for a date already snapshotted and checks it reproduces.
   *
   * <p><b>On a match nothing is written at all</b> — not the objects, not a manifest, not a run
   * row. The snapshot of record is the one taken first and the chain link the next date follows
   * stays exactly where it was; a verification that agreed is a log line, not a second snapshot.
   *
   * <p>On a mismatch a run <i>is</i> recorded, {@code FAILED}, naming both hashes. A disagreement
   * about a stored snapshot is precisely the thing that must not be left only in a log.
   */
  private SnapshotResult verifyAgainst(
      SnapshotRun established, LocalDate businessDate, Instant startedAt) {
    List<RegisteredFund> registeredFunds = funds.allFunds();
    Map<Long, Units> unitsInIssue = readUnitsInIssue(registeredFunds);
    BuiltPositions built = positions.build(businessDate, ledgerIdByFund(registeredFunds));

    String establishedSha = established.positionsSha256().orElseThrow();
    if (!establishedSha.equals(built.file().contentSha256())) {
      throw recorded(
          businessDate,
          startedAt,
          new SnapshotMismatchException(
              businessDate, establishedSha, built.file().contentSha256()));
    }

    List<SnapshotFundTotal> fundTotals;
    try {
      fundTotals = tieAgainstLedger(businessDate, registeredFunds, built, unitsInIssue);
    } catch (SnapshotFailedException e) {
      // The file reproduced but the book no longer ties to it — units in issue moved under a
      // snapshot already published. That is a finding, not a log line.
      throw recorded(businessDate, startedAt, e);
    }
    LOGGER.info(
        "snapshot verified: businessDate={} runOfRecord={} positions={}",
        businessDate,
        established.runId(),
        built.file().positionCount());
    return new SnapshotResult(established, fundTotals, Optional.empty(), false);
  }

  // ---- the tie ------------------------------------------------------------------------------

  private List<SnapshotFundTotal> tieAgainstLedger(
      LocalDate businessDate,
      List<RegisteredFund> registeredFunds,
      BuiltPositions built,
      Map<Long, Units> unitsInIssue) {
    Map<Long, FundTally> talliesByLedger = new HashMap<>();
    for (FundTally tally : built.file().fundTallies()) {
      talliesByLedger.put(tally.unitLedgerId(), tally);
    }

    List<SnapshotFundTotal> totals = new ArrayList<>(registeredFunds.size());
    for (RegisteredFund fund : registeredFunds) {
      FundTally tally = talliesByLedger.get(fund.unitLedgerId());
      totals.add(
          new SnapshotFundTotal(
              fund.fundId(),
              fund.unitLedgerId(),
              tally == null ? 0L : tally.positionCount(),
              tally == null ? Units.of(0L) : tally.units(),
              tally == null ? Money.of(0L, currencyOf(fund, built.navsByFund())) : tally.value(),
              unitsInIssue.getOrDefault(fund.unitLedgerId(), Units.of(0L))));
    }

    List<SnapshotFundTotal> untied = totals.stream().filter(total -> !total.ties()).toList();
    if (!untied.isEmpty()) {
      throw new SnapshotNotTiedException(businessDate, untied);
    }
    return totals;
  }

  /**
   * The currency a fund with <b>no rows in the file</b> records its zero total in.
   *
   * <p>Every fund holding positions took its currency from the NAV those positions were valued at,
   * which came from {@code reference.fund_identity.base_currency}. A fund with no positions has
   * nothing to take it from unless it happens to have been priced, so a fund never priced and never
   * held falls back to MXN. It is denominating a zero, and v1 is MXN-only ({@code KEY-TYPES.md}) —
   * but the column exists rather than being assumed, because an amount stored without its unit
   * stops being reproducible the day that stops being true.
   */
  private static CurrencyCode currencyOf(RegisteredFund fund, Map<FundId, SnapshotNav> navByFund) {
    SnapshotNav nav = navByFund.get(fund.fundId());
    return nav == null ? CurrencyCode.MXN : nav.nav().currency();
  }

  private static Map<FundId, Long> ledgerIdByFund(List<RegisteredFund> registeredFunds) {
    Map<FundId, Long> byFund = new LinkedHashMap<>(registeredFunds.size());
    for (RegisteredFund fund : registeredFunds) {
      byFund.put(fund.fundId(), fund.unitLedgerId());
    }
    return byFund;
  }

  /** {@code UNITS_IN_ISSUE} per fund, read through {@code LedgerPort} in batches. */
  private Map<Long, Units> readUnitsInIssue(List<RegisteredFund> registeredFunds) {
    Map<AccountId, Long> ledgerIdByAccount = new LinkedHashMap<>();
    for (RegisteredFund fund : registeredFunds) {
      ledgerIdByAccount.put(
          accountIds.derive(
              LedgerCodes.AccountCode.UNITS_IN_ISSUE, fund.fundId().value().toString()),
          fund.unitLedgerId());
    }
    List<AccountId> ids = List.copyOf(ledgerIdByAccount.keySet());
    Map<Long, Units> byLedger = new HashMap<>();
    int batch = properties.accountLookupBatchSize();
    for (int from = 0; from < ids.size(); from += batch) {
      for (LedgerAccount account :
          ledger.lookupAccounts(ids.subList(from, Math.min(from + batch, ids.size())))) {
        if (account instanceof LedgerAccount.Unit unit) {
          byLedger.put(unit.fundLedgerId(), unit.balance());
        }
      }
    }
    return byLedger;
  }

  // ---- settlement ---------------------------------------------------------------------------

  /**
   * Records a verification's failure as a {@code FAILED} run and hands the exception back to throw.
   *
   * <p>A verification that agrees writes no row — it is not a snapshot. One that disagrees must
   * leave one: a disagreement about a snapshot already published is exactly the thing that must not
   * exist only in a log.
   */
  private <E extends SnapshotFailedException> E recorded(
      LocalDate businessDate, Instant startedAt, E failure) {
    UUID runId = UUID.randomUUID();
    runs.open(runId, businessDate, startedAt);
    settleFailed(runId, businessDate, startedAt, failure.reason());
    return failure;
  }

  private void settleFailed(UUID runId, LocalDate businessDate, Instant startedAt, String reason) {
    Instant failedAt = clock.instant();
    runs.fail(runId, failedAt, Duration.between(startedAt, failedAt).toMillis(), reason);
    LOGGER.error(
        "snapshot failed: runId={} businessDate={} reason={}", runId, businessDate, reason);
  }

  /** {@code <prefix>/dt=YYYY-MM-DD/positions.ndjson.gz} — partitioned by date (phase-07 7.4). */
  private String positionsKeyFor(LocalDate businessDate) {
    return properties.keyPrefix() + "/dt=" + businessDate + "/positions.ndjson.gz";
  }

  private String manifestKeyFor(LocalDate businessDate) {
    return properties.keyPrefix() + "/dt=" + businessDate + "/manifest.json";
  }
}
