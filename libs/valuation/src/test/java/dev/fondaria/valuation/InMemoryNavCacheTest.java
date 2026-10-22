package dev.fondaria.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryNavCache} against in-memory fakes — no Redis and no database, because none of this
 * class's behaviour depends on either. The Redis implementation is proven separately against a real
 * container.
 *
 * <p><b>Every fund id is freshly generated</b> (AGENTS.md §4) and every assertion is over ids the
 * test created, so nothing here can pass or fail on what another test left behind.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryNavCacheTest {

  private static final Instant NOW = Instant.parse("2026-08-24T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration STALE_AFTER = Duration.ofHours(26);
  private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 8, 24);

  @Test
  void a_fund_that_has_never_been_struck_is_absent_rather_than_zero() {
    FakeRepository repository = new FakeRepository();
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    assertThat(cache.current(new FundId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void a_miss_reads_through_so_a_just_started_instance_serves_a_correct_price() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW));
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    // Nothing swept and nothing warm-started: the map is empty and the answer is still right.
    assertThat(cache.size()).isZero();
    assertThat(cache.current(fundId))
        .map(CachedNav::nav)
        .contains(Nav.of(1_0000_0000L, CurrencyCode.MXN));
    assertThat(cache.size()).isOne();
  }

  @Test
  void publishing_a_new_price_replaces_the_held_one_on_refresh() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW));
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);
    cache.refreshAll();

    repository.put(nav(fundId, VALUATION_DATE.plusDays(1), 1_0500_0000L, 2, NOW));
    cache.refresh(fundId);

    assertThat(cache.current(fundId))
        .hasValueSatisfying(
            held -> {
              assertThat(held.nav()).isEqualTo(Nav.of(1_0500_0000L, CurrencyCode.MXN));
              assertThat(held.navVersionId()).isEqualTo(2L);
            });
  }

  /**
   * The correction path Task 20.5 will write, exercised through the mechanism this task owns: a row
   * superseding the live one takes effect the moment the cache is told, not on the next interval.
   */
  @Test
  void a_correction_takes_effect_immediately_on_refresh_not_on_the_next_interval() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    FundNav original = nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW);
    repository.put(original);
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);
    cache.refreshAll();
    assertThat(cache.current(fundId)).map(CachedNav::navVersionId).contains(1L);

    repository.supersede(original, 9_9000_0000L, 2, "provider restated the balance");
    cache.refresh(fundId);

    assertThat(cache.current(fundId))
        .hasValueSatisfying(
            held -> {
              assertThat(held.nav()).isEqualTo(Nav.of(9_9000_0000L, CurrencyCode.MXN));
              assertThat(held.navVersionId()).isEqualTo(2L);
            });
  }

  @Test
  void a_price_older_than_the_configured_age_is_served_marked_stale_not_withheld() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(
        nav(fundId, VALUATION_DATE.minusDays(3), 1_0000_0000L, 1, NOW.minus(Duration.ofDays(3))));
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    assertThat(cache.current(fundId))
        .hasValueSatisfying(
            held -> {
              assertThat(held.stale()).isTrue();
              // Served, not withheld: a stale price is still the platform's best price.
              assertThat(held.nav()).isEqualTo(Nav.of(1_0000_0000L, CurrencyCode.MXN));
            });
  }

  @Test
  void a_price_inside_the_configured_age_is_not_stale() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(
        nav(fundId, VALUATION_DATE.minusDays(1), 1_0000_0000L, 1, NOW.minus(Duration.ofHours(25))));
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    assertThat(cache.current(fundId)).map(CachedNav::stale).contains(false);
  }

  /**
   * AGENTS.md §4: a suite that only exercises rising prices will pass on a broken implementation.
   * The series falls, recovers and falls again, and the cache is asserted to hold each value in
   * turn — including the ones below where it started.
   */
  @Test
  void a_falling_and_volatile_series_is_held_correctly_at_every_step() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    long[] series = {
      1_0000_0000L, 1_0250_0000L, 9_800_0000L, 9_100_0000L, 9_950_0000L, 8_400_0000L, 1_0100_0000L
    };
    List<Long> observed = new ArrayList<>();
    for (int day = 0; day < series.length; day++) {
      repository.put(nav(fundId, VALUATION_DATE.plusDays(day), series[day], day + 1, NOW));
      cache.refreshAll();
      observed.add(cache.current(fundId).orElseThrow().nav().scaled());
    }

    assertThat(observed)
        .containsExactly(
            1_0000_0000L,
            1_0250_0000L,
            9_800_0000L,
            9_100_0000L,
            9_950_0000L,
            8_400_0000L,
            1_0100_0000L);
    // The series genuinely falls, and ends below a peak it reached earlier.
    assertThat(observed.get(2)).isLessThan(observed.get(1));
    assertThat(observed.get(5)).isLessThan(observed.get(0));
  }

  @Test
  void a_sweep_holds_one_live_price_for_each_fund_and_drops_funds_no_longer_returned() {
    FundId kept = new FundId(UUID.randomUUID());
    FundId dropped = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(nav(kept, VALUATION_DATE, 1_0000_0000L, 1, NOW));
    repository.put(nav(dropped, VALUATION_DATE, 2_0000_0000L, 2, NOW));
    InMemoryNavCache cache = cache(repository, NavSnapshotStore.NONE);

    assertThat(cache.refreshAll()).isEqualTo(2);

    repository.remove(dropped);
    assertThat(cache.refreshAll()).isOne();
    assertThat(cache.size()).isOne();
  }

  /**
   * The acceptance criterion "Redis unavailable does not stop NAV reads", proven against a store
   * ruder than any real outage: this one throws from every method rather than degrading quietly.
   */
  @Test
  void a_snapshot_store_that_throws_from_every_call_does_not_stop_a_read() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW));
    InMemoryNavCache cache = cache(repository, new ExplodingSnapshotStore());

    assertThat(cache.warmStart()).isZero();
    assertThat(cache.refreshAll()).isOne();
    assertThat(cache.current(fundId))
        .map(CachedNav::nav)
        .contains(Nav.of(1_0000_0000L, CurrencyCode.MXN));
    // Swallowed, but not silently: the breach of contract is counted.
    assertThat(cache.snapshotFailures()).isPositive();
  }

  @Test
  void a_warm_start_serves_a_price_before_any_sweep_and_the_database_still_wins() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    FakeSnapshotStore snapshots = new FakeSnapshotStore();
    snapshots.save(nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW));

    InMemoryNavCache cache = cache(repository, snapshots);
    assertThat(cache.warmStart()).isOne();
    assertThat(cache.current(fundId)).map(CachedNav::navVersionId).contains(1L);

    // The database is authoritative: once it has a newer row, the sweep replaces the snapshot.
    repository.put(nav(fundId, VALUATION_DATE.plusDays(1), 1_0400_0000L, 2, NOW));
    cache.refreshAll();
    assertThat(cache.current(fundId)).map(CachedNav::navVersionId).contains(2L);
  }

  @Test
  void every_held_price_is_written_through_to_the_snapshot_store() {
    FundId fundId = new FundId(UUID.randomUUID());
    FakeRepository repository = new FakeRepository();
    repository.put(nav(fundId, VALUATION_DATE, 1_0000_0000L, 1, NOW));
    FakeSnapshotStore snapshots = new FakeSnapshotStore();

    cache(repository, snapshots).refreshAll();

    assertThat(snapshots.loadAll())
        .singleElement()
        .satisfies(row -> assertThat(row.fundId()).isEqualTo(fundId));
  }

  @Test
  void a_non_positive_stale_age_is_refused_at_construction() {
    assertThatThrownBy(
            () ->
                new InMemoryNavCache(
                    new FakeRepository(), NavSnapshotStore.NONE, CLOCK, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InMemoryNavCache cache(FundNavRepository repository, NavSnapshotStore snapshots) {
    return new InMemoryNavCache(repository, snapshots, CLOCK, STALE_AFTER);
  }

  private static FundNav nav(
      FundId fundId, LocalDate valuationDate, long navScaled, long versionId, Instant publishedAt) {
    return new FundNav(
        versionId,
        fundId,
        valuationDate,
        Nav.of(navScaled, CurrencyCode.MXN),
        Units.of(1_000_00000000L),
        "MODEL_B",
        null,
        null,
        publishedAt);
  }

  /**
   * Holds rows exactly as {@code reference.fund_nav} does, supersession included, so "live" means
   * the same thing here as it does in SQL: the row nothing points at.
   */
  private static final class FakeRepository implements FundNavRepository {
    private final Map<FundId, List<FundNav>> rows = new HashMap<>();

    void put(FundNav row) {
      rows.computeIfAbsent(row.fundId(), k -> new ArrayList<>()).add(row);
    }

    void remove(FundId fundId) {
      rows.remove(fundId);
    }

    /** Appends a correction pointing at {@code original}, exactly as Task 20.5 will. */
    void supersede(FundNav original, long navScaled, long versionId, String reason) {
      put(
          new FundNav(
              versionId,
              original.fundId(),
              original.valuationDate(),
              Nav.of(navScaled, original.nav().currency()),
              original.unitsInIssue(),
              original.source(),
              original.navVersionId(),
              reason,
              original.publishedAt()));
    }

    @Override
    public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
      return rows.getOrDefault(fundId, List.of()).stream()
          .filter(row -> row.valuationDate().equals(valuationDate) && row.supersedesId() == null)
          .findFirst();
    }

    @Override
    public FundNav strike(FundNav candidate) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
      return rows.getOrDefault(fundId, List.of()).stream()
          .filter(
              row ->
                  row.valuationDate().equals(valuationDate)
                      && rows.getOrDefault(fundId, List.of()).stream()
                          .noneMatch(
                              s -> Long.valueOf(row.navVersionId()).equals(s.supersedesId())))
          .findFirst();
    }

    @Override
    public FundNav correct(FundNav candidate) {
      throw new UnsupportedOperationException("not used by this path — see #supersede");
    }

    @Override
    public Optional<FundNav> findLatestLive(FundId fundId) {
      List<FundNav> all = rows.getOrDefault(fundId, List.of());
      return all.stream()
          .filter(
              row ->
                  all.stream()
                      .noneMatch(s -> Long.valueOf(row.navVersionId()).equals(s.supersedesId())))
          .max(Comparator.comparing(FundNav::valuationDate).thenComparing(FundNav::navVersionId));
    }

    @Override
    public List<FundNav> findLatestLivePerFund() {
      return rows.keySet().stream().map(this::findLatestLive).flatMap(Optional::stream).toList();
    }
  }

  private static final class FakeSnapshotStore implements NavSnapshotStore {
    private final Map<FundId, FundNav> held = new HashMap<>();

    @Override
    public List<FundNav> loadAll() {
      return List.copyOf(held.values());
    }

    @Override
    public void save(FundNav nav) {
      held.put(nav.fundId(), nav);
    }
  }

  /**
   * Stands in for a Redis that is down. The real {@code RedisNavSnapshotStore} swallows its own
   * failures, so this is deliberately ruder than reality: it proves the cache reads correctly even
   * when the store breaks its contract and throws.
   */
  private static final class ExplodingSnapshotStore implements NavSnapshotStore {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public List<FundNav> loadAll() {
      calls.incrementAndGet();
      throw new IllegalStateException("Redis is down");
    }

    @Override
    public void save(FundNav nav) {
      calls.incrementAndGet();
      throw new IllegalStateException("Redis is down");
    }
  }
}
