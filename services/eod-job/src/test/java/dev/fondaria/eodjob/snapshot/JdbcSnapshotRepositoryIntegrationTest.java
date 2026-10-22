package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code workflow.snapshot_run} and {@code workflow.snapshot_fund_total} against a real PostgreSQL
 * ({@code V28}).
 *
 * <p>Every assertion is scoped to ids this test created. Fixtures are reused across runs (AGENTS.md
 * §4), so the business date is randomised far in the past: a fixed date would make {@code
 * latestCompletedBefore} answer with whatever an earlier run left behind, and the manifest chain
 * would be asserted against a stranger's snapshot.
 */
@SpringBootTest
class JdbcSnapshotRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private SnapshotRepository repository;
  @Autowired private JdbcClient jdbc;

  private LocalDate businessDate;
  private FundId fundId;
  private long unitLedgerId;

  @BeforeEach
  void freshFixture() {
    businessDate = anUnusedBusinessDate();
    fundId = new FundId(UUID.randomUUID());
    unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '1900-01-01', 100000000, now())
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("unitScale", Units.SCALE)
        .update();
  }

  @Test
  @DisplayName("a run is RUNNING before it settles, and COMPLETED with its hashes once it has")
  void aRunIsOpenedBeforeItIsSettled() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    assertThat(repository.latestCompletedFor(businessDate))
        .as("an unfinished run is not a snapshot, so nothing may compare itself against it")
        .isEmpty();

    repository.complete(completed(runId, startedAt, "aa"), List.of(total(100L, 100L)));

    SnapshotRun settled = repository.latestCompletedFor(businessDate).orElseThrow();
    assertThat(settled.status()).isEqualTo(SnapshotStatus.COMPLETED);
    assertThat(settled.positionsSha256()).contains(sha("aa"));
    assertThat(settled.positionCount()).isEqualTo(7L);
    assertThat(settled.previousManifestSha256()).isEmpty();
  }

  @Test
  @DisplayName("a settled run cannot be settled again")
  void completingATwiceSettledRunIsRefused() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(completed(runId, startedAt, "aa"), List.of(total(100L, 100L)));

    assertThatThrownBy(
            () -> repository.complete(completed(runId, startedAt, "bb"), List.of(total(1L, 1L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer RUNNING");
  }

  @Test
  @DisplayName("a failed run records why, and is never mistaken for a snapshot")
  void aFailedRunRecordsWhy() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    repository.fail(runId, startedAt.plusMillis(5), 5L, "1 fund(s) did not tie to units in issue");

    assertThat(repository.latestCompletedFor(businessDate)).isEmpty();
    assertThat(
            jdbc.sql("SELECT failure_reason FROM workflow.snapshot_run WHERE run_id = :runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .isEqualTo("1 fund(s) did not tie to units in issue");
  }

  @Test
  @DisplayName("the chain link is the latest completed run for a strictly earlier date")
  void theChainFollowsBusinessDateOrder() {
    UUID yesterdayId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(yesterdayId, businessDate.minusDays(1), startedAt);
    repository.complete(
        completed(yesterdayId, businessDate.minusDays(1), startedAt, "aa"),
        List.of(total(100L, 100L)));

    UUID todayId = UUID.randomUUID();
    repository.open(todayId, businessDate, startedAt);
    repository.complete(completed(todayId, startedAt, "bb"), List.of(total(100L, 100L)));

    assertThat(repository.latestCompletedBefore(businessDate).orElseThrow().runId())
        .as("the previous business date's snapshot, not the one just taken for this date")
        .isEqualTo(yesterdayId);
    // Deliberately not asserted: that nothing exists before yesterday. Fixtures are reused across
    // runs (AGENTS.md §4), so an earlier run's snapshot at some other random past date may well be
    // there, and asserting its absence would fail on what a previous run left behind.
  }

  @Test
  @DisplayName("fund totals are append-only — the tie's own evidence cannot be edited away")
  void fundTotalsAreAppendOnly() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(completed(runId, startedAt, "aa"), List.of(total(90L, 100L)));

    assertThat(repository.fundTotalsOf(runId)).hasSize(1);
    assertThat(repository.fundTotalsOf(runId).get(0).driftScaled()).isEqualTo(-10L);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE workflow.snapshot_fund_total SET units_scaled = units_in_issue"
                            + " WHERE run_id = :runId")
                    .param("runId", runId)
                    .update())
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM workflow.snapshot_fund_total WHERE run_id = :runId")
                    .param("runId", runId)
                    .update())
        .hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("only one run per date may become the snapshot of record")
  void onlyOneRunPerDateMayBeOfRecord() {
    // Two instances of eod-job can both build a file for one date and both find no completed run.
    // Without the partial unique index the later one to settle silently becomes the record while
    // the earlier one's bytes are what is actually in the bucket — an audit row naming a hash the
    // stored object does not have, undetectable because the store is write-only by design.
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(first, businessDate, startedAt);
    repository.open(second, businessDate, startedAt);

    repository.complete(completed(first, startedAt, "aa"), List.of(total(100L, 100L)));

    assertThatThrownBy(
            () -> repository.complete(completed(second, startedAt, "bb"), List.of(total(1L, 1L))))
        .hasMessageContaining("snapshot_run_one_of_record_per_date");
    assertThat(repository.latestCompletedFor(businessDate).orElseThrow().runId()).isEqualTo(first);
  }

  @Test
  @DisplayName("a pass records what it is about to store before the bytes leave")
  void intentIsRecordedOnTheRunningRow() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    repository.recordIntent(runId, "snapshots/dt=x/positions.ndjson.gz", sha("aa"));

    assertThat(
            jdbc.sql("SELECT positions_sha256 FROM workflow.snapshot_run WHERE run_id = :runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .as("a pass that dies at the store still says what it meant to put there")
        .isEqualTo(sha("aa"));
    assertThat(repository.latestCompletedFor(businessDate))
        .as("and it is still not a snapshot — nothing has completed")
        .isEmpty();
  }

  @Test
  @DisplayName("a settled run cannot record a new intent")
  void intentCannotBeRecordedAfterSettling() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(completed(runId, startedAt, "aa"), List.of(total(100L, 100L)));

    assertThatThrownBy(() -> repository.recordIntent(runId, "snapshots/dt=x/p.gz", sha("bb")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer RUNNING");
  }

  @Test
  @DisplayName("a run may not claim a hash that is not one")
  void hashesAreConstrained() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    assertThatThrownBy(
            () ->
                repository.complete(
                    new SnapshotRun(
                        runId,
                        businessDate,
                        SnapshotStatus.COMPLETED,
                        startedAt,
                        Optional.of(startedAt.plusMillis(1)),
                        Optional.of(1L),
                        1L,
                        1,
                        Optional.of("snapshots/dt=x/positions.ndjson.gz"),
                        Optional.of("NOT-A-DIGEST"),
                        Optional.of("snapshots/dt=x/manifest.json"),
                        Optional.of(sha("bb")),
                        Optional.empty(),
                        Optional.empty()),
                    List.of()))
        .hasMessageContaining("snapshot_run_hashes_are_hex");
  }

  // ---- fixture ---------------------------------------------------------------------------

  @Test
  @DisplayName("completedRunsAscending returns completed runs ordered by business date ascending")
  void completedRunsAscendingReturnsInOrder() {
    LocalDate d1 = anUnusedBusinessDate();
    LocalDate d2 = d1.plusDays(1);
    LocalDate d3 = d1.plusDays(2);

    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();
    UUID run3 = UUID.randomUUID();
    Instant t = now();

    repository.open(run1, d1, t);
    repository.complete(completed(run1, d1, t, "d1"), List.of(total(100L, 100L)));

    repository.open(run3, d3, t);
    repository.complete(completed(run3, d3, t, "d3"), List.of(total(100L, 100L)));

    repository.open(run2, d2, t);
    repository.complete(completed(run2, d2, t, "d2"), List.of(total(100L, 100L)));

    List<SnapshotRun> ascending = repository.completedRunsAscending();
    List<SnapshotRun> testRuns =
        ascending.stream()
            .filter(r -> r.runId().equals(run1) || r.runId().equals(run2) || r.runId().equals(run3))
            .toList();

    assertThat(testRuns).hasSize(3);
    assertThat(testRuns.get(0).businessDate()).isEqualTo(d1);
    assertThat(testRuns.get(1).businessDate()).isEqualTo(d2);
    assertThat(testRuns.get(2).businessDate()).isEqualTo(d3);
  }

  private SnapshotFundTotal total(long unitsScaled, long unitsInIssueScaled) {
    return new SnapshotFundTotal(
        fundId,
        unitLedgerId,
        7L,
        Units.of(unitsScaled),
        Money.of(1_234L, CurrencyCode.MXN),
        Units.of(unitsInIssueScaled));
  }

  private SnapshotRun completed(UUID runId, Instant startedAt, String seed) {
    return completed(runId, businessDate, startedAt, seed);
  }

  private SnapshotRun completed(UUID runId, LocalDate date, Instant startedAt, String seed) {
    return new SnapshotRun(
        runId,
        date,
        SnapshotStatus.COMPLETED,
        startedAt,
        Optional.of(startedAt.plusMillis(3)),
        Optional.of(3L),
        7L,
        1,
        Optional.of("snapshots/dt=" + date + "/positions.ndjson.gz"),
        Optional.of(sha(seed)),
        Optional.of("snapshots/dt=" + date + "/manifest.json"),
        Optional.of(sha(seed + "-manifest")),
        Optional.empty(),
        Optional.empty());
  }

  /** A well-formed lowercase-hex digest, so the schema's CHECK is satisfied by real shapes. */
  private static String sha(String seed) {
    return RenderedObject.hexDigestOf(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * A business date no other run has used.
   *
   * <p>Somewhere between the years 2300 and 9999. {@code workflow.snapshot_run} is never cleaned —
   * a completed snapshot is a record, and this suite has no business deleting one — so a date this
   * run picks that an earlier run also picked would make {@code latestCompletedFor} answer with a
   * stranger's snapshot, and this suite would halt on a mismatch against a file it never built. A
   * ten-thousand-day window collides about one run in a hundred once a few dozen have accumulated;
   * this one is 2.8 million days wide.
   *
   * <p>The upper bound is 9999 rather than PostgreSQL's own {@code date} ceiling for a mundane
   * reason worth recording: {@code LocalDate.toString()} renders a five-digit year as {@code
   * +867907-04-01}, and {@code COPY}'s text format rejects the leading sign.
   */
  private static LocalDate anUnusedBusinessDate() {
    return LocalDate.of(2300, 1, 1).plusDays(ThreadLocalRandom.current().nextLong(0L, 2_800_000L));
  }

  /** Microsecond truncation, matching StorableClock — timestamptz keeps no more. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
