package dev.fondaria.eodjob.signoff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * What the platform pages on for sign-off (Task 7.5a). Same division of labour as {@code
 * ReconciliationMetrics} and {@code SnapshotMetrics}: the exception stops the chain, these meters
 * are how a human finds out. No participant, organization or fund identifier on any meter
 * (docs/ENGINEERING-RULES.md §7).
 *
 * <ul>
 *   <li>{@code eod_signoff_blocked} — {@code 1} while the latest sweep could not sign off the
 *       current business date. <b>The gauge an alert watches</b>, with a time-of-day window in the
 *       rule (Task 7.6 / Phase 11) because "not signed off yet" is normal until the close runs.
 *   <li>{@code eod_signoff_announce_pending} — how many sign-offs are recorded but whose {@code
 *       close.signed-off.v1} is not yet confirmed. Set from a table read every sweep, not derived
 *       from one pass, so it stays accurate across a business-date rollover: a date whose event was
 *       stranded by an overnight broker outage keeps this above zero until a later sweep publishes
 *       it. Non-zero for more than a sweep or two means Redpanda is unreachable and the statement
 *       chain is not advancing.
 *   <li>{@code eod_signoff_total} — dates signed off, ever. Its rate is one per business day on a
 *       healthy platform.
 * </ul>
 */
@Component
public class SignoffMetrics {

  private final AtomicLong blocked = new AtomicLong(1L);
  private final AtomicLong announcePending = new AtomicLong(0L);
  private final Counter signedOff;

  public SignoffMetrics(MeterRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    // Starts at 1: before the first successful sweep, no close is signed off, and the gate agrees.
    registry.gauge("eod_signoff_blocked", blocked, AtomicLong::doubleValue);
    registry.gauge("eod_signoff_announce_pending", announcePending, AtomicLong::doubleValue);
    this.signedOff =
        Counter.builder("eod_signoff_total")
            .description("Business dates whose daily close was signed off")
            .register(registry);
  }

  /** Records a sweep that signed off (or found already signed off) the current date. */
  public void record(SignoffResult result) {
    blocked.set(0L);
    if (result.newlySignedOff()) {
      signedOff.increment();
    }
  }

  /** Records a sweep blocked by an upstream gate, or one that could not complete. */
  public void recordBlocked() {
    blocked.set(1L);
  }

  /**
   * Sets the pending-announcement gauge from a count read out of {@code workflow.close_signoff}.
   */
  public void setAnnouncePending(int count) {
    announcePending.set(count);
  }
}
