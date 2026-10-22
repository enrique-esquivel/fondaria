package dev.fondaria.valuation;

import dev.fondaria.rules.FundNav;
import java.util.List;

/**
 * A warm-start convenience for {@link NavCache}, and nothing more (Task 20.6).
 *
 * <p><b>Never authoritative.</b> {@code reference.fund_nav} is the price of record; this exists
 * only so an instance that has just started can serve a price before its first database sweep.
 * Every method here may fail, return nothing, or return something out of date without any caller
 * changing behaviour — which is why none of them throws and why {@link #NONE} is a complete
 * implementation.
 *
 * <p>The seam is here rather than in the service for the same reason {@link ReportedBalanceSource}
 * is: {@code libs/valuation} states what it needs, and the vendor implementation — Redis, via
 * Lettuce — lives in the service that owns the connection. This module has no Redis dependency and
 * must not acquire one.
 */
public interface NavSnapshotStore extends AutoCloseable {

  /** Every snapshot held, best effort. An unreachable store returns empty, never throws. */
  List<FundNav> loadAll();

  /**
   * Records one fund's live NAV, best effort. A failure is swallowed — the next refresh rewrites.
   */
  void save(FundNav nav);

  /**
   * Releases whatever connection the implementation holds. {@code AutoCloseable} because an
   * implementation may own a socket — {@code java.lang}, not a framework, so this stays a
   * dependency-free module.
   *
   * <p>Declared without {@code throws}, narrowing {@link AutoCloseable#close()}: a store that
   * cannot be closed has nothing a caller could usefully do about it, and the default does nothing
   * at all.
   */
  @Override
  default void close() {
    // Nothing to release.
  }

  /**
   * No warm start at all: the cache reads the database on its first miss and on its first sweep.
   *
   * <p>The default rather than a degenerate case. Redis is optional here by design (AC: "Redis
   * unavailable does not stop NAV reads"), and a deployment that has not configured it should get a
   * working cache, not a broken one.
   */
  NavSnapshotStore NONE =
      new NavSnapshotStore() {
        @Override
        public List<FundNav> loadAll() {
          return List.of();
        }

        @Override
        public void save(FundNav nav) {
          // Intentionally nothing.
        }

        @Override
        public String toString() {
          return "NavSnapshotStore.NONE";
        }
      };
}
