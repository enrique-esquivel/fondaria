package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.eodjob.config.ProjectionCatchUpProperties;
import dev.fondaria.ledger.LedgerPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Whether the projection has caught up with the ledger, so that an internal-reconciliation pass is
 * worth running (ADR-0038).
 *
 * <p>Live against live (see {@link ReconciliationService}), a pass run while the projection is
 * still draining the day's CDC events can only fail, and each one re-reads the whole history. A
 * close with half a million events behind it used to record 37–47 such failures. This check costs
 * one ledger round trip and one aggregate over a few dozen checkpoint rows.
 *
 * <p><b>It is never a correctness input.</b> Skipping records no run, so the gate stays closed
 * exactly as before; running records a run whose outcome the full comparison alone decides. The
 * worst a wrong answer here can do is run a pass that fails (as every pass used to) or delay one.
 * The delay is bounded: every way of being unable to tell — no ledger head, no checkpoint rows, a
 * projection that has stopped advancing for {@link ProjectionCatchUpProperties#stallAfter} — runs
 * the pass, so a stuck projection still produces the failing run and the breaks an operator pages
 * on.
 */
@Component
public class ProjectionCatchUp {

  private final LedgerPort ledger;
  private final ProjectionProgress progress;
  private final ProjectionCatchUpProperties properties;

  public ProjectionCatchUp(
      LedgerPort ledger, ProjectionProgress progress, ProjectionCatchUpProperties properties) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.progress = Objects.requireNonNull(progress, "progress must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  /** Reads both heads and decides. */
  public Decision check() {
    Optional<Instant> ledgerHead = ledger.latestTransferTimestamp();
    if (ledgerHead.isEmpty()) {
      return Decision.run("the ledger reports no head");
    }
    long headNanos = nanos(ledgerHead.get());
    ProjectionProgress.Snapshot snapshot = progress.against(headNanos, properties.quietPeriod());
    if (snapshot.highestCommittedNanos().isEmpty()) {
      return Decision.run("the projection has no checkpoint");
    }
    long highest = snapshot.highestCommittedNanos().get();
    if (highest >= headNanos && snapshot.busyConsumersBehind() == 0) {
      return Decision.run("the projection has reached the ledger head");
    }
    Duration idle = snapshot.sinceLastAdvance().orElse(Duration.ZERO);
    if (idle.compareTo(properties.stallAfter()) >= 0) {
      return Decision.run("the projection has not advanced for " + idle.toSeconds() + " s");
    }
    return Decision.skip(
        Duration.ofNanos(Math.max(0L, headNanos - highest)), snapshot.busyConsumersBehind());
  }

  private static long nanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
  }

  /**
   * @param runPass whether a pass should run now
   * @param reason why, for the log
   * @param gap how far the projection's highest commit is behind the ledger head (zero when it has
   *     reached it but a consumer is still draining)
   * @param busyConsumersBehind consumers still draining behind the head
   */
  public record Decision(boolean runPass, String reason, Duration gap, long busyConsumersBehind) {

    static Decision run(String reason) {
      return new Decision(true, reason, Duration.ZERO, 0L);
    }

    static Decision skip(Duration gap, long busyConsumersBehind) {
      return new Decision(
          false, "the projection is behind the ledger head", gap, busyConsumersBehind);
    }
  }
}
