package dev.fondaria.projection.consume;

import dev.fondaria.projection.config.CheckpointProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The raw signals Task 11.4's alert reads (folded in from the struck Phase 4 Task 4.5, {@code
 * phase-11-observability.md} Task 11.2): whether the CDC pipeline is keeping up, and whether it is
 * still receiving anything at all. Distinguishing "idle" from "stalled" is that alert's job, not
 * this class's — this only exports the numbers.
 *
 * <ul>
 *   <li>{@code last_timestamp{group}} — this pod's head, computed exactly as {@link
 *       ProjectionCheckpoint#HEAD} computes the worker's (ADR-0032 §4): {@code MIN} over this pod's
 *       consumers that committed within the active window, falling back to their {@code MAX} when
 *       all are idle. Each consumer's mark is the value its checkpoint row actually stored. Across
 *       pods, alerts take {@code max(ledger_cdc_lag_seconds)}, which is the lag of the most-behind
 *       active consumer anywhere. {@code group} is a single fixed value ({@link
 *       ProjectionCheckpoint#PROJECTION_NAME}), not an open-ended label.
 *   <li>{@code ledger_head_timestamp} — the ledger timestamp of the most recently <i>received</i>
 *       record on either topic, updated regardless of whether it has been committed yet. If this
 *       stops advancing, nothing new is arriving — independent of whether this consumer itself is
 *       keeping up.
 *   <li>{@code ledger_cdc_lag_seconds{group}} — {@code now() - last_timestamp}, exactly as
 *       specified. A gauge, not a counter: it can shrink as well as grow.
 * </ul>
 *
 * <p>Ledger timestamps are u64 nanoseconds since the epoch, as canonical decimal strings — see
 * {@code LedgerEventDecoder}. Parsed with {@link BigInteger} and narrowed with {@code
 * longValueExact()}: a real nanosecond timestamp fits a signed {@code long} until the year 2262, so
 * this is exact today, and if that ever stopped being true this deliberately throws — a metrics
 * component silently reporting a wrapped or truncated lag value would be worse than this worker
 * halting on an unprocessable record, the same "never skip, halt instead" choice {@link
 * ProjectionCheckpoint}'s own class javadoc describes for the sink itself.
 */
@Component
public class CdcLagMetrics {

  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  /** Per consumer id: the highest timestamp its row stored, and when it last advanced. */
  private final Map<String, Mark> marks = new ConcurrentHashMap<>();

  /**
   * The highest mark this pod has ever recorded. Survives {@link #forgetIdleSince}, so a pod whose
   * every consumer stalls past the retention keeps reporting a growing lag instead of resetting to
   * "nothing consumed yet" while the queues still hold work.
   */
  private final AtomicLong highestNanos = new AtomicLong();

  private final AtomicLong headTimestampNanos = new AtomicLong();
  private final Clock clock;
  private final Duration activeWindow;

  public CdcLagMetrics(MeterRegistry registry, Clock clock, CheckpointProperties properties) {
    this.clock = clock;
    this.activeWindow = properties.activeWindow();
    registry.gauge(
        "last_timestamp",
        Tags.of("group", ProjectionCheckpoint.PROJECTION_NAME),
        this,
        metrics -> (double) metrics.podHeadNanos());
    registry.gauge("ledger_head_timestamp", headTimestampNanos, AtomicLong::doubleValue);
    registry.gauge(
        "ledger_cdc_lag_seconds",
        Tags.of("group", ProjectionCheckpoint.PROJECTION_NAME),
        this,
        CdcLagMetrics::lagSeconds);
  }

  /**
   * Called by {@link ProjectionCheckpoint#advanceTo} with what {@code consumerId}'s row actually
   * stored. Monotonic per consumer, like the row itself.
   */
  public void lastTimestampAdvancedTo(String consumerId, String ledgerTimestampNanos) {
    long candidate = new BigInteger(ledgerTimestampNanos).longValueExact();
    Instant now = clock.instant();
    marks.merge(
        consumerId,
        new Mark(candidate, now),
        (current, next) -> new Mark(Math.max(current.nanos(), next.nanos()), now));
    highestNanos.accumulateAndGet(candidate, Math::max);
  }

  /**
   * Forget consumers that have not advanced since {@code cutoff} — dead consumers of this process
   * (a reconnect gets a new consumer tag), so the map stays bounded. Called by {@link
   * ProjectionCheckpoint#removeStale} with the same retention it prunes rows by.
   */
  void forgetIdleSince(Instant cutoff) {
    marks.values().removeIf(mark -> mark.advancedAt().isBefore(cutoff));
  }

  /**
   * This pod's head: {@code MIN} over consumers active within the window, else the highest mark it
   * has ever recorded (even for consumers since forgotten); {@code 0} when nothing has been
   * committed yet.
   */
  long podHeadNanos() {
    Instant activeSince = clock.instant().minus(activeWindow);
    long minActive = Long.MAX_VALUE;
    long maxAll = highestNanos.get();
    for (Mark mark : marks.values()) {
      maxAll = Math.max(maxAll, mark.nanos());
      if (mark.advancedAt().isAfter(activeSince)) {
        minActive = Math.min(minActive, mark.nanos());
      }
    }
    return minActive == Long.MAX_VALUE ? maxAll : minActive;
  }

  /**
   * Called by {@link LedgerEventListener} right after a record decodes, before {@link
   * ProjectionSink#accept} — "received," here, means off the topic and readable, not yet committed.
   * A record that fails to decode never reaches this call; that halts the consumer entirely (see
   * {@link LedgerEventListener}'s own class javadoc), at which point both this gauge and {@code
   * last_timestamp} simply stop advancing and {@code ledger_cdc_lag_seconds} grows — the correct
   * signal for that failure mode without this method needing to run before decoding.
   */
  public void headReceivedAt(String ledgerTimestampNanos) {
    advanceMonotonic(headTimestampNanos, ledgerTimestampNanos);
  }

  /**
   * Only ever moves {@code holder} forward. {@code fondaria.projection.concurrency} drains multiple
   * partitions on independent threads with no ordering guarantee between them (see {@link
   * LedgerEventListener}'s own class javadoc — "per-account sequential," not per-worker), and
   * neither topic is timestamp-ordered across partitions, so a call carrying an older event can
   * genuinely run after one carrying a newer one. Without this guard the gauge would flap backward
   * exactly when several partitions are busy — the one time this signal most needs to be
   * trustworthy — the same regression {@link ProjectionCheckpoint#advanceTo}'s SQL {@code GREATEST}
   * already guards against for the persisted watermark.
   */
  private static void advanceMonotonic(AtomicLong holder, String ledgerTimestampNanos) {
    long candidate = new BigInteger(ledgerTimestampNanos).longValueExact();
    holder.updateAndGet(current -> Math.max(current, candidate));
  }

  private double lagSeconds() {
    long lastNanos = podHeadNanos();
    if (lastNanos == 0L) {
      // Nothing consumed yet this process. Reporting a multi-decade lag against the epoch
      // would be a false alarm at every fresh start; report no lag until there is a real
      // watermark to measure against.
      return 0.0;
    }
    BigInteger nowNanos =
        BigInteger.valueOf(clock.instant().getEpochSecond()).multiply(NANOS_PER_SECOND);
    BigInteger lagNanos = nowNanos.subtract(BigInteger.valueOf(lastNanos));
    return lagNanos.doubleValue() / NANOS_PER_SECOND.doubleValue();
  }

  private record Mark(long nanos, Instant advancedAt) {}
}
