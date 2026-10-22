package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.ledger.LedgerCodes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the platform pages on when the book stops tying (phase-07 Task 7.2: "drift blocks the rest
 * of the chain and pages").
 *
 * <p><b>Blocking and paging are separate mechanisms and both are needed.</b> {@link
 * ReconciliationGate} stops the close; these meters are how a human finds out. A gate that blocked
 * silently would turn a detected break into a missing statement run nobody explains until someone
 * asks.
 *
 * <p>Four meters, and no participant, organization or fund identifier on any of them — a label is
 * as much a log line as a message is (docs/ENGINEERING-RULES.md §7), and an alert only has to say
 * <i>that</i> the close is blocked. Which fund is in {@code workflow.reconciliation_break}, behind
 * authentication.
 *
 * <ul>
 *   <li>{@code eod_reconciliation_blocked} — {@code 1} while the latest pass did not pass. <b>The
 *       gauge to alert on</b>, because it stays raised until a pass succeeds rather than firing
 *       once and clearing itself.
 *   <li>{@code eod_reconciliation_breaks_total} — findings ever raised. Its rate distinguishes one
 *       persistent break from a book coming apart.
 *   <li>{@code eod_reconciliation_duration} — how long a pass takes. The phase requires the
 *       duration be recorded; it is also the first thing to look at when a close starts missing its
 *       window.
 *   <li>{@code projection_drift_minor} — signed gauge of ledger minus projection balances in minor
 *       units (cents for cash, 1e-8 for units). Emitted per ledger id (484 for cash, fund unit
 *       ledger ids for funds).
 * </ul>
 *
 * <p>The alert rule and its runbook are Task 7.6's and Phase 11's to write — this class is the
 * signal they read.
 */
@Component
public class ReconciliationMetrics {

  private final MeterRegistry registry;
  private final AtomicLong blocked = new AtomicLong(1L);
  private final Counter breaksRaised;
  private final Timer duration;
  private final ConcurrentMap<Long, AtomicLong> driftByLedger = new ConcurrentHashMap<>();

  public ReconciliationMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    // Starts at 1: before the first pass of a process, nothing has proved the book ties, and the
    // gate agrees — an unproved close is a blocked close.
    registry.gauge("eod_reconciliation_blocked", blocked, AtomicLong::doubleValue);
    this.breaksRaised =
        Counter.builder("eod_reconciliation_breaks_total")
            .description("Internal-reconciliation breaks raised, of any scope")
            .register(registry);
    this.duration =
        Timer.builder("eod_reconciliation_duration")
            .description("How long one internal-reconciliation pass took")
            .register(registry);

    // Cash drift gauge initialized up front with ledger_id 484
    AtomicLong cashDrift = new AtomicLong(0L);
    driftByLedger.put(LedgerCodes.CASH_LEDGER_ID, cashDrift);
    registry.gauge(
        "projection_drift_minor",
        Tags.of("ledger_id", String.valueOf(LedgerCodes.CASH_LEDGER_ID)),
        cashDrift,
        AtomicLong::doubleValue);
  }

  /** Records a completed pass. */
  public void record(ReconciliationResult result) {
    blocked.set(result.passed() ? 0L : 1L);
    breaksRaised.increment(result.breaks().size());
    result.run().durationMillis().ifPresent(millis -> duration.record(Duration.ofMillis(millis)));

    // Update projection_drift_minor per fund unit-ledger (LEDGER_PARTICIPANT_UNITS vs
    // PROJECTION_UNITS)
    for (FundUnitTie tie : result.unitTies()) {
      long unitDrift = tie.ledgerParticipantUnits().scaled() - tie.projectionUnits().scaled();
      AtomicLong holder =
          driftByLedger.computeIfAbsent(
              tie.unitLedgerId(),
              id -> {
                AtomicLong newHolder = new AtomicLong();
                registry.gauge(
                    "projection_drift_minor",
                    Tags.of("ledger_id", String.valueOf(id)),
                    newHolder,
                    AtomicLong::doubleValue);
                return newHolder;
              });
      holder.set(unitDrift);
    }

    // Update projection_drift_minor for cash (ledger 484)
    long cashDrift = 0L;
    for (CashAccountTie tie : result.cashTies()) {
      cashDrift += (tie.ledgerCash().cents() - tie.projectionCash().cents());
    }
    if (cashDrift == 0L) {
      for (ReconciliationBreak b : result.breaks()) {
        if (b.scope() == BreakScope.CASH_ACCOUNT) {
          if (Math.abs(b.driftScaled()) > Math.abs(cashDrift)) {
            cashDrift = b.driftScaled();
          }
        }
      }
    }
    AtomicLong cashHolder =
        driftByLedger.computeIfAbsent(
            LedgerCodes.CASH_LEDGER_ID,
            id -> {
              AtomicLong newHolder = new AtomicLong();
              registry.gauge(
                  "projection_drift_minor",
                  Tags.of("ledger_id", String.valueOf(id)),
                  newHolder,
                  AtomicLong::doubleValue);
              return newHolder;
            });
    cashHolder.set(cashDrift);
  }

  /** Records a pass that could not finish. Not a pass, so the gauge stays raised. */
  public void recordFailure() {
    blocked.set(1L);
  }

  /**
   * Value of the projection_drift_minor gauge for a given ledger id, or empty if never recorded.
   */
  public Optional<Long> driftForLedger(long ledgerId) {
    return Optional.ofNullable(driftByLedger.get(ledgerId)).map(AtomicLong::get);
  }
}
