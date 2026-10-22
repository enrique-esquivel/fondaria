package dev.fondaria.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Clock} that runs ahead of real time by an offset dialed forward from {@code
 * reference.demo_clock} (Task D.1, {@code phase-D-demo.md}) — how the demo says "a day passes"
 * without every job's own idea of "today" needing to change.
 *
 * <h2>Why an offset, not a fixed instant</h2>
 *
 * <p>Every job in this platform derives "today" from an injected {@link Clock} and {@code
 * BusinessCalendar.businessDateOf(instant)} — never from {@code Instant.now()} directly. Moving
 * that clock forward is therefore the one lever that makes the close run again, an allotment reach
 * its dealing date, and a valuation dated "tomorrow" stop being refused, without a second code path
 * for demo time. {@link #instant()} is real time plus the last offset this instance has seen, so it
 * keeps advancing between polls exactly as a real clock would, rather than freezing at whatever
 * instant was last read from the database.
 *
 * <h2>What this deliberately does not touch</h2>
 *
 * <p>TigerBeetle's own commit timestamps are the cluster's wall clock and are never influenced by
 * this class — see {@code phase-D-demo.md} § "Time, honestly" for why that seam is acceptable for
 * the demo. This clock only changes what the six services reading it believe "now" is.
 *
 * <h2>Polling, not pushing</h2>
 *
 * <p>The offset is written exactly once, from {@code demo-tools advance} over a direct JDBC
 * connection (Task D.2) — deliberately not through an API endpoint, because a clock-advance
 * endpoint on a money service is an attack surface nobody wants in production even disabled. Every
 * service instead <b>polls</b> {@code reference.demo_clock} once a second on a dedicated background
 * thread and caches the last value it saw. {@link #instant()} only ever reads that cached value —
 * it never blocks on I/O, never takes the poll lock, and never throws, so a slow or unreachable
 * database degrades polling, not every timestamp this platform mints.
 *
 * <p>A failed poll keeps the last known offset and logs at WARN exactly once per failure episode,
 * not once per tick — six services polling once a second would otherwise turn one outage into a log
 * line a second, indistinguishable from six separate incidents.
 *
 * <h2>Enablement</h2>
 *
 * <p>Built only when {@code DEMO_CLOCK_ENABLED=true}; every service's {@code clock()} bean method
 * returns {@link StorableClock#systemUTC()} otherwise and never constructs this class at all, so
 * {@code reference.demo_clock} is never read in production. See each service's application class.
 *
 * <p>{@link ReentrantLock}, never {@code synchronized} (docs/ENGINEERING-RULES.md §4): the guarded
 * section performs JDBC I/O, and {@code synchronized} around I/O pins a virtual thread.
 */
public final class SimulatedClock extends Clock implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimulatedClock.class);

  /** How often the background thread re-reads {@code reference.demo_clock} (phase-D-demo.md). */
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  /**
   * Explicit timeout on the poll's JDBC call (docs/ENGINEERING-RULES.md §4: every external call has
   * one).
   */
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

  private static final String SELECT_OFFSET =
      "SELECT offset_seconds FROM reference.demo_clock WHERE id = 1";

  private final Clock delegate;
  private final ZoneId zone;
  private final SharedState state;

  private SimulatedClock(Clock delegate, ZoneId zone, SharedState state) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.zone = Objects.requireNonNull(zone, "zone must not be null");
    this.state = Objects.requireNonNull(state, "state must not be null");
  }

  /**
   * The clock every service's {@code clock()} bean builds when {@code DEMO_CLOCK_ENABLED=true}.
   * Starts polling immediately, on its own daemon thread, and primes itself with one synchronous
   * read before returning — a service that has just started should not report real time for a whole
   * poll interval after the demo has already moved the offset forward.
   *
   * @param delegate the real clock this offsets from — always {@link StorableClock#systemUTC()} in
   *     production wiring, never a raw system clock
   * @param dataSource the service's own {@link DataSource}. Never held past the constructor beyond
   *     what the poll needs — a connection is opened and closed on every tick, exactly like any
   *     other short-lived query this platform issues
   */
  public static SimulatedClock wrapping(Clock delegate, DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    return new SimulatedClock(delegate, delegate.getZone(), SharedState.polling(dataSource));
  }

  /**
   * Test seam: builds a clock over a caller-supplied offset reader instead of a real {@link
   * DataSource}, and does not start a background thread — a test calls {@link #poll()} itself and
   * asserts on the result, rather than sleeping for a real tick.
   */
  static SimulatedClock wrappingForTest(Clock delegate, OffsetReader reader) {
    return new SimulatedClock(delegate, delegate.getZone(), new SharedState(reader, false));
  }

  /** One poll, synchronous. Package-private: production never calls this directly. */
  void poll() {
    state.poll();
  }

  /**
   * The offset currently held, in seconds. Public so a service's {@code demoClock} health component
   * (Task D.1's design; wired in D.2) can report it without reaching into this package.
   */
  public long offsetSeconds() {
    return state.offsetSeconds.get();
  }

  @Override
  public Instant instant() {
    return delegate.instant().plusSeconds(state.offsetSeconds.get());
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    Objects.requireNonNull(zone, "zone must not be null");
    return new SimulatedClock(delegate.withZone(zone), zone, state);
  }

  /**
   * Stops the background poll thread. Registered as a Spring destroy method automatically (Spring
   * infers {@code close} on any {@link AutoCloseable} bean) — a service shutting down must not
   * leave a thread polling a database connection pool that is closing at the same time.
   *
   * <p>Shared across every {@link #withZone(ZoneId)} view of this clock: there is one poll thread
   * per {@link #wrapping(Clock, DataSource)} call, not per view.
   */
  @Override
  public void close() {
    state.close();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SimulatedClock clock
        && delegate.equals(clock.delegate)
        && zone.equals(clock.zone)
        && state == clock.state;
  }

  @Override
  public int hashCode() {
    return Objects.hash(delegate, zone, state);
  }

  @Override
  public String toString() {
    return "SimulatedClock[" + delegate + ", offsetSeconds=" + state.offsetSeconds.get() + "]";
  }

  /**
   * How a poll actually reads the offset. Production goes through JDBC; a test supplies a lambda.
   */
  @FunctionalInterface
  interface OffsetReader {
    long readOffsetSeconds() throws Exception;
  }

  /**
   * The mutable poll state, held once per {@link #wrapping(Clock, DataSource)} call and shared by
   * every {@link #withZone(ZoneId)} view — offset and thread ownership belong to the clock as a
   * whole, not to any one zoned view of it.
   */
  private static final class SharedState {

    private final OffsetReader reader;
    private final ReentrantLock pollLock = new ReentrantLock();
    private final AtomicLong offsetSeconds = new AtomicLong(0);
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;

    /** Test instances (no scheduler): {@code startPolling} left {@code false}. */
    private SharedState(OffsetReader reader, boolean startPolling) {
      this.reader = Objects.requireNonNull(reader, "reader must not be null");
      this.scheduler =
          startPolling
              ? Executors.newSingleThreadScheduledExecutor(SharedState::daemonThread)
              : null;
    }

    /**
     * Separate from the constructor so {@code this::poll}/{@code this} never escapes before the
     * object is fully initialized — the constructor only assigns final fields.
     */
    private void startPolling() {
      poll(); // Prime synchronously so instant() reflects the current offset immediately.
      scheduler.scheduleAtFixedRate(
          this::poll, POLL_INTERVAL.toMillis(), POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    static SharedState polling(DataSource dataSource) {
      SharedState state = new SharedState(() -> readViaJdbc(dataSource), true);
      state.startPolling();
      return state;
    }

    /**
     * Never blocks {@link SimulatedClock#instant()} — that method only ever reads {@link
     * #offsetSeconds}. This lock only ever guards one poll against another: a poll slower than the
     * interval (a database blip) must not run two JDBC calls concurrently on the same clock.
     *
     * <p>Catches {@link Throwable}, not {@link Exception}: an uncaught throwable escaping a {@link
     * ScheduledExecutorService#scheduleAtFixedRate} task cancels every future execution of it
     * silently, which would freeze the offset forever with nothing further logged — worse than the
     * failure this method exists to contain.
     */
    void poll() {
      if (!pollLock.tryLock()) {
        return; // A poll is already in flight; the next tick will catch up.
      }
      try {
        long read = reader.readOffsetSeconds();
        offsetSeconds.set(read);
        failureLogged.set(false);
      } catch (Throwable e) {
        if (failureLogged.compareAndSet(false, true)) {
          LOGGER.warn("Demo clock offset poll failed; keeping the last known offset", e);
        }
      } finally {
        pollLock.unlock();
      }
    }

    /**
     * Waits for an in-flight poll to actually stop before returning, so a connection pool this
     * clock was still reading from can close right behind it without racing a poll for the same
     * connection — the hazard {@link SimulatedClock#close()}'s own javadoc names.
     */
    void close() {
      if (scheduler != null) {
        scheduler.shutdownNow();
        try {
          scheduler.awaitTermination(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static Thread daemonThread(Runnable task) {
      Thread thread = new Thread(task, "simulated-clock-poll");
      thread.setDaemon(true);
      return thread;
    }

    /**
     * A missing row is treated exactly like any other poll failure (an absent {@code id = 1} row is
     * always a fault — the seed row is inserted by {@code V31__demo_clock.sql} and nothing this
     * platform does deletes it), never as offset zero: silently rewinding six services' clocks to
     * real time because a row was momentarily unreadable would be indistinguishable from the demo
     * actually resetting time, and about dated financial records.
     */
    private static long readViaJdbc(DataSource dataSource) throws Exception {
      try (Connection connection = dataSource.getConnection();
          PreparedStatement statement = connection.prepareStatement(SELECT_OFFSET)) {
        statement.setQueryTimeout((int) POLL_TIMEOUT.toSeconds());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            throw new java.sql.SQLException("reference.demo_clock has no id = 1 row");
          }
          return resultSet.getLong(1);
        }
      }
    }
  }
}
