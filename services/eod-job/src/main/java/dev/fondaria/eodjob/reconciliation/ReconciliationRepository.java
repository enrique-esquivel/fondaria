package dev.fondaria.eodjob.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a reconciliation pass is recorded, and where the gate reads it back.
 *
 * <p>Three writes rather than one, deliberately. {@link #open} lands before any comparison, so a
 * process that dies mid-pass leaves a row that is visibly not a pass; {@link #complete} and {@link
 * #fail} settle it. A single write at the end would leave a crashed pass indistinguishable from one
 * that never started — and the operator's first question after a missed close is which of the two
 * it was.
 */
public interface ReconciliationRepository {

  /** Records a pass as {@code RUNNING}, before anything is compared. */
  void open(UUID runId, LocalDate businessDate, Instant startedAt);

  /** Settles a pass, and writes its findings, in one transaction. */
  void complete(ReconciliationRun run, List<ReconciliationBreak> breaks);

  /**
   * Settles a pass that could not finish: {@code FAILED}, with nothing found because nothing ran.
   */
  void fail(UUID runId, Instant failedAt, long durationMillis);

  /**
   * The latest <b>evidence</b> for a business date: the run that settled most recently, or — only
   * when nothing has settled — an unfinished one. The gate reads this and nothing else.
   *
   * <p><b>Completion order, not start order</b>, and the difference is a way a real break can hide.
   * Two instances may reconcile the same date at once: a redeploy overlap, or a replica nobody
   * meant to scale. Say A starts first and B starts second; B finishes quickly and passes, and A
   * finishes later having found genuine drift. Ordered by {@code started_at}, B is "latest"
   * forever, and A's finding never reaches the gate — the close proceeds on a book with a detected
   * break in it. Ordered by {@code completed_at}, A's finding is the newer evidence the moment it
   * lands, which is what it is.
   *
   * <p><b>An unfinished run sorts behind every settled one, and that is deliberate too.</b> A row
   * left {@code RUNNING} by a process that died would otherwise block its date forever — no later
   * run could ever displace it, and only a manual {@code UPDATE} would reopen the close. Sorting it
   * last means a crashed pass blocks only until something completes, while a date whose <i>only</i>
   * run is unfinished still blocks, because that row is then the latest thing there is.
   */
  Optional<ReconciliationRun> latestFor(LocalDate businessDate);

  /** The findings of one pass, in the order they were raised. */
  List<ReconciliationBreak> breaksOf(UUID runId);
}
