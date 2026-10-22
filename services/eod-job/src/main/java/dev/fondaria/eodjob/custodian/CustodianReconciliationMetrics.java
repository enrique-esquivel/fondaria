package dev.fondaria.eodjob.custodian;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the platform pages on for custodian reconciliation (Task 7.3: "drift blocks... and a late
 * statement raises an alert"). Counterpart of {@code ReconciliationMetrics} for Task 7.2 — same
 * reasoning: blocking ({@link CustodianReconciliationGate}) and paging (this class) are separate
 * mechanisms, no participant/organization/fund identifier on any meter, and the alert rule and
 * runbook are Task 7.6's and Phase 11's to write.
 *
 * <ul>
 *   <li>{@code eod_custodian_reconciliation_blocked} — {@code 1} while the latest pass did not
 *       pass, for any reason (a real break, or a missing statement).
 *   <li>{@code eod_custodian_statements_late_total} — missing-statement occurrences across every
 *       pass. Its rate distinguishes one slow provider from a feed that stopped.
 *   <li>{@code eod_custodian_breaks_total} — real breaks opened, tagged {@code category}.
 *   <li>{@code eod_custodian_timing_differences_total} — timing differences opened. Counted
 *       separately from real breaks because it is not, by itself, a page-worthy signal.
 *   <li>{@code eod_custodian_breaks_escalated_total} — breaks that aged past their category's
 *       threshold this pass.
 *   <li>{@code eod_custodian_reconciliation_duration} — how long a pass took.
 * </ul>
 */
@Component
public class CustodianReconciliationMetrics {

  private final AtomicLong blocked = new AtomicLong(1L);
  private final Counter statementsLate;
  private final Counter realBreaksRaised;
  private final Counter timingDifferencesRaised;
  private final Counter breaksEscalated;
  private final Timer duration;

  public CustodianReconciliationMetrics(MeterRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    registry.gauge("eod_custodian_reconciliation_blocked", blocked, AtomicLong::doubleValue);
    this.statementsLate =
        Counter.builder("eod_custodian_statements_late_total")
            .description("Provider accounts missing their statement when a pass ran")
            .register(registry);
    this.realBreaksRaised =
        Counter.builder("eod_custodian_breaks_total")
            .description("Real custodian-reconciliation breaks newly opened")
            .register(registry);
    this.timingDifferencesRaised =
        Counter.builder("eod_custodian_timing_differences_total")
            .description("Timing differences newly opened")
            .register(registry);
    this.breaksEscalated =
        Counter.builder("eod_custodian_breaks_escalated_total")
            .description("Custodian-reconciliation breaks escalated for ageing past threshold")
            .register(registry);
    this.duration =
        Timer.builder("eod_custodian_reconciliation_duration")
            .description("How long one custodian-reconciliation pass took")
            .register(registry);
  }

  public void record(CustodianReconciliationResult result) {
    blocked.set(result.passed() ? 0L : 1L);
    statementsLate.increment(result.run().missingStatementCount());
    realBreaksRaised.increment(
        result.breaksTouched().stream().filter(b -> !b.category().isTimingDifference()).count());
    timingDifferencesRaised.increment(
        result.breaksTouched().stream().filter(b -> b.category().isTimingDifference()).count());
    breaksEscalated.increment(result.escalatedCount());
    result.run().durationMillis().ifPresent(millis -> duration.record(Duration.ofMillis(millis)));
  }

  public void recordFailure() {
    blocked.set(1L);
  }
}
