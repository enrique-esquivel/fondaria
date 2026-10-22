package dev.fondaria.valuation;

import dev.fondaria.common.FundId;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link NavCache} over a {@link ConcurrentHashMap}, refreshed from {@link FundNavRepository} (Task
 * 20.6).
 *
 * <p><b>A map and a lock, because that is genuinely all this needs.</b> One price per fund, a
 * handful of funds, every instance free to hold its own copy — there is nothing here for a
 * distributed cache to coordinate. The authoritative rows are in {@code reference.fund_nav} and
 * this converges on them; it never decides anything.
 *
 * <p><b>{@link ReentrantLock}, never {@code synchronized}</b> (docs/ENGINEERING-RULES.md §4):
 * {@link #refreshAll()} holds it across a database query, and {@code synchronized} around I/O pins
 * a virtual thread. The lock guards only the sweep, so reads never wait on one.
 *
 * <p><b>No logging.</b> This module has no slf4j dependency and does not acquire one for a cache;
 * the service-side wiring logs sweeps and Redis failures where a logger already exists.
 */
public final class InMemoryNavCache implements NavCache {

  private final FundNavRepository repository;
  private final NavSnapshotStore snapshots;
  private final Clock clock;
  private final Duration staleAfter;

  private final Map<FundId, FundNav> live = new ConcurrentHashMap<>();

  /**
   * Serializes full sweeps against each other. Not against {@link #refresh(FundId)} or reads: a
   * per-fund put is atomic on its own key, and a sweep overtaking a single-fund refresh writes the
   * same row the refresh just read.
   */
  private final ReentrantLock sweepLock = new ReentrantLock();

  /** See {@link #snapshotFailures()}. */
  private final AtomicInteger snapshotFailures = new AtomicInteger();

  /**
   * @param staleAfter how old a published price may be before it is served marked stale. Daily NAV
   *     (O-21) makes something over 24 hours the meaningful setting — long enough that yesterday's
   *     price on a normal morning is not flagged, short enough that a provider going quiet is.
   */
  public InMemoryNavCache(
      FundNavRepository repository, NavSnapshotStore snapshots, Clock clock, Duration staleAfter) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter must not be null");
    if (staleAfter.isNegative() || staleAfter.isZero()) {
      throw new IllegalArgumentException("staleAfter must be positive");
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>A miss reads through to the repository rather than reporting absence, so an instance that
   * has just started — or one asked about a fund struck since its last sweep — answers correctly
   * instead of claiming the fund has no price. A fund that genuinely has none is not negatively
   * cached: the case is transient (it ends at the fund's first strike) and the alternative is a
   * cache that remembers a "no" it was never told to forget.
   */
  @Override
  public Optional<CachedNav> current(FundId fundId) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    FundNav held = live.get(fundId);
    if (held == null) {
      held = readThrough(fundId);
    }
    return Optional.ofNullable(held).map(row -> CachedNav.of(row, clock.instant(), staleAfter));
  }

  /**
   * {@inheritDoc}
   *
   * <p>A snapshot of the map, priced against one clock reading so every entry in a single response
   * is judged stale against the same instant rather than against a clock that moved mid-iteration.
   */
  @Override
  public List<CachedNav> currentAll() {
    Instant now = clock.instant();
    return live.values().stream().map(row -> CachedNav.of(row, now, staleAfter)).toList();
  }

  @Override
  public void refresh(FundId fundId) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    repository.findLatestLive(fundId).ifPresent(this::hold);
  }

  @Override
  public int refreshAll() {
    sweepLock.lock();
    try {
      List<FundNav> rows = repository.findLatestLivePerFund();
      // Replace rather than merge: a fund whose rows were removed from under us should
      // disappear from the cache too, and the query already returns every fund that has a
      // price. Held entries are overwritten by key, so a concurrent reader never observes
      // the map empty.
      live.keySet().retainAll(rows.stream().map(FundNav::fundId).toList());
      rows.forEach(this::hold);
      return live.size();
    } finally {
      sweepLock.unlock();
    }
  }

  /**
   * Populates from the snapshot store, for an instance that has just started and has not swept yet.
   * Rows already held win — a database read is authoritative over a snapshot, whatever the order.
   *
   * @return how many funds the warm start contributed; zero if the store could not be read
   */
  public int warmStart() {
    List<FundNav> snapshot;
    try {
      snapshot = snapshots.loadAll();
    } catch (RuntimeException e) {
      snapshotFailures.incrementAndGet();
      return 0;
    }
    int loaded = 0;
    for (FundNav row : snapshot) {
      if (live.putIfAbsent(row.fundId(), row) == null) {
        loaded++;
      }
    }
    return loaded;
  }

  /** Funds currently held. For tests and for the sweep's own return value. */
  public int size() {
    return live.size();
  }

  /**
   * How many times the snapshot store threw where its contract says it must not.
   *
   * <p>Not a log line, because this module has no logger; a counter instead, so the failure is
   * observable rather than silent and Phase 11 has something to surface. A non-zero value here is a
   * bug in the store, not an outage — {@code RedisNavSnapshotStore} handles an unreachable Redis
   * itself and returns normally.
   */
  public int snapshotFailures() {
    return snapshotFailures.get();
  }

  private FundNav readThrough(FundId fundId) {
    Optional<FundNav> found = repository.findLatestLive(fundId);
    found.ifPresent(this::hold);
    return found.orElse(null);
  }

  /**
   * <b>A failing snapshot store must never fail a read</b> — the acceptance criterion is explicit
   * ("Redis unavailable does not stop NAV reads"). {@link NavSnapshotStore} already promises not to
   * throw and the Redis implementation keeps that promise, but this is a price used to value
   * participants' money and the guarantee is worth having structurally rather than contractually:
   * the write-through is best effort and the in-memory put has already happened.
   */
  private void hold(FundNav row) {
    live.put(row.fundId(), row);
    try {
      snapshots.save(row);
    } catch (RuntimeException e) {
      snapshotFailures.incrementAndGet();
    }
  }
}
