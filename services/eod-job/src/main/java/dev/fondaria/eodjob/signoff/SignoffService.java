package dev.fondaria.eodjob.signoff;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.eodjob.custodian.CustodianReconciliationGate;
import dev.fondaria.eodjob.reconciliation.ReconciliationGate;
import dev.fondaria.eodjob.snapshot.SnapshotRun;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sign-off (Task 7.5a): the fact that a business date's daily close has been proved three ways and
 * may now be reported from.
 *
 * <h2>The one thing this does</h2>
 *
 * <p>It checks the three upstream gates — internal reconciliation ({@code ReconciliationGate}, Task
 * 7.2), custodian reconciliation ({@code CustodianReconciliationGate}, Task 7.3) and the dense
 * snapshot ({@link SnapshotGate}, Task 7.4) — and, only if all three are open, writes one {@code
 * workflow.close_signoff} row and publishes {@code close.signed-off.v1}. Any gate closed and it
 * does nothing, propagating that gate's own exception so the reason reaches the log unchanged. Each
 * gate hands back the run it validated, so the row records the exact run without a second, racier
 * read that a concurrent settle could answer differently.
 *
 * <h2>The row first, then the event</h2>
 *
 * <p>{@code workflow.close_signoff} is the system of record; the event is derived from it ({@code
 * ADR-0021}). The row is written (with {@code announced_at} null), <i>then</i> the event is
 * published, <i>then</i> {@code announced_at} is set. A crash between the write and the publish, or
 * a broker outage, leaves a row that says "signed off, not announced". Every sweep re-publishes
 * <b>every</b> such row first — {@link #reannouncePending} — not only the current date's, so a
 * broker outage that outlasts the cut-off still recovers rather than stranding yesterday's
 * sign-off. A consumer of the event is idempotent on the business date, so a re-publish is
 * harmless. This is the platform's standard two-fact rule (docs/ENGINEERING-RULES.md §"Data flow").
 *
 * <h2>Automatic, for now</h2>
 *
 * <p>No human approves a sign-off in v1. {@code phase-09-identity.md} makes it a four-eyes action
 * once identity exists; until then "all three gates open" is the whole condition. And a
 * reconciliation re-run that fails <i>after</i> a date is signed off does not revoke the row —
 * {@code workflow.close_signoff} has no status — which is an operational incident (Task 7.6's
 * "missed sign-off" runbook), not a state this service models.
 */
@Service
public class SignoffService {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignoffService.class);

  private final SignoffRepository signoffs;
  private final ReconciliationGate internalGate;
  private final CustodianReconciliationGate custodianGate;
  private final SnapshotGate snapshotGate;
  private final CloseSignedOffPublisher publisher;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public SignoffService(
      SignoffRepository signoffs,
      ReconciliationGate internalGate,
      CustodianReconciliationGate custodianGate,
      SnapshotGate snapshotGate,
      CloseSignedOffPublisher publisher,
      BusinessCalendar calendar,
      Clock clock) {
    this.signoffs = Objects.requireNonNull(signoffs, "signoffs must not be null");
    this.internalGate = Objects.requireNonNull(internalGate, "internalGate must not be null");
    this.custodianGate = Objects.requireNonNull(custodianGate, "custodianGate must not be null");
    this.snapshotGate = Objects.requireNonNull(snapshotGate, "snapshotGate must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * One sweep: re-announce every sign-off whose event is still pending, then sign off the business
   * date the clock currently falls in.
   *
   * @throws dev.fondaria.eodjob.reconciliation.CloseBlockedException internal reconciliation has
   *     not passed for today
   * @throws dev.fondaria.eodjob.custodian.CustodianCloseBlockedException custodian reconciliation
   *     has not passed for today
   * @throws SnapshotNotTakenException no dense snapshot of record exists for today
   */
  public SignoffResult signOff() {
    reannouncePending();
    return signOffFor(calendar.businessDateOf(clock.instant()));
  }

  /**
   * How many sign-offs are recorded but not yet announced — what {@code SignoffMetrics} reports.
   */
  public int pendingAnnouncementCount() {
    return signoffs.findUnannounced().size();
  }

  /** Re-publishes {@code close.signed-off.v1} for every row whose event is not yet confirmed. */
  public void reannouncePending() {
    for (CloseSignoff pending : signoffs.findUnannounced()) {
      announce(pending);
    }
  }

  /** Signs off one business date, or re-announces the sign-off it already has. */
  SignoffResult signOffFor(LocalDate businessDate) {
    Optional<CloseSignoff> existing = signoffs.find(businessDate);
    if (existing.isPresent()) {
      return ensureAnnounced(existing.get());
    }

    var internal = internalGate.requirePassed(businessDate);
    var custodian = custodianGate.requirePassed(businessDate);
    SnapshotRun snapshot = snapshotGate.requireTaken(businessDate);

    CloseSignoff row =
        new CloseSignoff(
            businessDate,
            clock.instant(),
            internal.runId(),
            custodian.runId(),
            snapshot.runId(),
            snapshot.positionCount(),
            snapshot
                .positionsObjectKey()
                .orElseThrow(() -> new IllegalStateException("snapshot of record names no file")),
            snapshot
                .manifestObjectKey()
                .orElseThrow(
                    () -> new IllegalStateException("snapshot of record names no manifest")),
            Optional.empty());

    if (!signoffs.insertIfAbsent(row)) {
      // Another eod-job instance signed this date off between the find above and here.
      return ensureAnnounced(signoffs.find(businessDate).orElseThrow());
    }

    LOGGER.info(
        "daily close signed off: businessDate={} reconciliationRunId={} custodianRunId={}"
            + " snapshotRunId={} positions={}",
        businessDate,
        internal.runId(),
        custodian.runId(),
        snapshot.runId(),
        snapshot.positionCount());

    return new SignoffResult(row, true, announce(row));
  }

  private SignoffResult ensureAnnounced(CloseSignoff row) {
    if (row.isAnnounced()) {
      return new SignoffResult(row, false, true);
    }
    return new SignoffResult(row, false, announce(row));
  }

  /**
   * Publishes {@code close.signed-off.v1} and records that it was, returning whether it succeeded.
   *
   * <p>A failure is swallowed here rather than thrown: the sign-off is already recorded, and the
   * correct response is to try again on the next sweep, not to unwind. A publish that fails leaves
   * the row un-announced; a publish that succeeds but whose {@code markAnnounced} write then fails
   * also leaves it un-announced, so the next sweep re-publishes (a duplicate the consumer ignores)
   * and retries the write. {@link SignoffRunner} feeds {@code eod_signoff_announce_pending} from
   * {@link #pendingAnnouncementCount()}, which stays raised until one of those retries lands.
   */
  private boolean announce(CloseSignoff row) {
    CloseSignedOffEvent event =
        new CloseSignedOffEvent(
            row.businessDate(),
            row.signedOffAt(),
            row.reconciliationRunId(),
            row.custodianRunId(),
            row.snapshotRunId(),
            row.snapshotObjectKey(),
            row.snapshotManifestKey(),
            row.snapshotPositionCount(),
            isLastBusinessDayOfMonth(row.businessDate()));
    try {
      publisher.publish(event);
      signoffs.markAnnounced(row.businessDate(), clock.instant());
      return true;
    } catch (PublishFailedException e) {
      LOGGER.warn(
          "close {} is signed off but close.signed-off.v1 is not out yet: {}",
          row.businessDate(),
          e.getMessage());
      return false;
    } catch (RuntimeException e) {
      // The event may already be on the broker; only the announce write failed. The next sweep
      // re-publishes and retries the write — both idempotent.
      LOGGER.warn(
          "close {} could not be marked announced; will retry: {}",
          row.businessDate(),
          e.getMessage());
      return false;
    }
  }

  private boolean isLastBusinessDayOfMonth(LocalDate businessDate) {
    return !YearMonth.from(calendar.nextBusinessDate(businessDate))
        .equals(YearMonth.from(businessDate));
  }
}
