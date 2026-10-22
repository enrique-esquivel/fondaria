package dev.fondaria.common;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A {@link Clock} whose instants survive being written down and read back <b>unchanged</b>.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>{@code Clock.systemUTC()} reports whatever resolution the host offers — microseconds on macOS,
 * <b>nanoseconds on Linux</b>. Every timestamp this platform records lands in a PostgreSQL {@code
 * timestamptz}, which stores <b>microseconds</b>. So on Linux an in-memory object carries three
 * digits that its own stored row does not, and the same resource renders differently depending on
 * whether the answer came from the write path (or a cache in front of it) and from the database:
 *
 * <pre>
 *   POST  → "statusUpdatedAt": "2026-09-02T02:03:48.295553188Z"   (in memory)
 *   POST  → "statusUpdatedAt": "2026-09-02T02:03:48.295553Z"      (replayed from PostgreSQL)
 * </pre>
 *
 * <p>That is a real defect and not a cosmetic one. A response that changes shape depending on which
 * layer answered it breaks the idempotency contract this platform leans on hardest — a replayed
 * submission is supposed to be "a slower answer, never a different one" — and it defeats any
 * read-after-write comparison, which is how reconciliation is checked. It was invisible for months
 * because the integration suite only ever ran on developers' macOS machines, where the truncation
 * is a no-op; the first CI job to run it on Linux (Task 6.3) failed on it immediately.
 *
 * <h2>Why the fix is here and not at each call site</h2>
 *
 * <p>Truncating where a timestamp is <i>rendered</i>, or where one particular service writes, would
 * leave every other mint site holding a value its store cannot keep — and there are six services
 * with the identical shape. Truncating at the source means no in-memory object anywhere can ever
 * carry precision the platform is unable to persist, which is a property that holds by construction
 * rather than by everyone remembering.
 *
 * <p><b>This deliberately couples a {@code platform-common} type to a storage fact</b> — {@link
 * #RESOLUTION} is microseconds because that is what PostgreSQL keeps, and it is the coarsest store
 * the platform writes an application-minted instant to. The alternative is a clock that is pure and
 * a fleet of objects that are wrong. Should a coarser store ever appear, this constant is the one
 * place to change.
 *
 * <p><b>Not a truncation of ledger time.</b> TigerBeetle's own commit timestamps are nanoseconds
 * and come off the ledger, never off this clock; nothing here touches them.
 *
 * <p>Immutable and thread-safe, like every {@link Clock}.
 */
public final class StorableClock extends Clock {

  /**
   * The finest resolution every store this platform writes to can hold. PostgreSQL {@code
   * timestamptz} is microsecond-resolution, and it is the coarsest of them.
   */
  public static final ChronoUnit RESOLUTION = ChronoUnit.MICROS;

  private final Clock delegate;

  private StorableClock(Clock delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
  }

  /** The system UTC clock, truncated to {@link #RESOLUTION}. What every service wires. */
  public static Clock systemUTC() {
    return wrapping(Clock.systemUTC());
  }

  /**
   * Wraps any clock — a fixed one in a test, an offset one for the demo's accelerated time.
   *
   * <p>Wrapping a clock that already truncates returns it unchanged, so the guarantee cannot be
   * doubled up into something surprising.
   */
  public static Clock wrapping(Clock delegate) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    return delegate instanceof StorableClock ? delegate : new StorableClock(delegate);
  }

  @Override
  public Instant instant() {
    return delegate.instant().truncatedTo(RESOLUTION);
  }

  @Override
  public ZoneId getZone() {
    return delegate.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new StorableClock(delegate.withZone(zone));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StorableClock clock && delegate.equals(clock.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode() ^ StorableClock.class.hashCode();
  }

  @Override
  public String toString() {
    return "StorableClock[" + delegate + "]";
  }
}
