package dev.fondaria.valuationapi.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.CachedNav;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import dev.fondaria.valuationapi.support.ValuationIntegrationTest;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The NAV cache against a real PostgreSQL and a real Redis (Task 20.6).
 *
 * <p><b>Every fund id is freshly generated and every assertion is over ids this test created</b>
 * (docs/ENGINEERING-RULES.md §4). That matters more than usual here: the Redis keys are global
 * rather than per-test, and fixtures are reused across local runs, so {@code loadAll()}
 * legitimately returns whatever earlier runs left behind. Nothing below counts entries or asserts a
 * total — each assertion filters to a fund this test registered. {@code FLUSHALL} is deliberately
 * never called: the fixture is shared, and wiping it would break whatever else is using it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NavCacheIntegrationTest extends ValuationIntegrationTest {

  @Autowired private FundNavRepository repository;
  @Autowired private NavSnapshotStore snapshots;
  @Autowired private InMemoryNavCache cache;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  @DisplayName("the cache matches fund_nav's live row after convergence")
  void theCacheMatchesTheLiveRowAfterConvergence() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav struck = repository.strike(candidate(fundId, date, 1_050_000_000L, 1_000));

    cache.refreshAll();

    assertThat(cache.current(fundId))
        .hasValueSatisfying(
            held -> {
              assertThat(held.navVersionId()).isEqualTo(struck.navVersionId());
              assertThat(held.nav()).isEqualTo(Nav.of(1_050_000_000L, CurrencyCode.MXN));
              assertThat(held.valuationDate()).isEqualTo(date);
            });
  }

  /**
   * The correction path, exercised through the mechanism this task owns. The superseding row is
   * inserted directly — Task 20.5 owns the write path and is not built — but the table's own
   * trigger still forbids {@code UPDATE} and {@code DELETE}, so this is an append exactly as a real
   * correction would be.
   */
  @Test
  @DisplayName("superseding a NAV takes effect on the next refresh, not on the next interval")
  void supersedingANavTakesEffectImmediately() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original = repository.strike(candidate(fundId, date, 1_000_000_000L, 1_000));
    cache.refreshAll();
    assertThat(cache.current(fundId))
        .map(CachedNav::navVersionId)
        .contains(original.navVersionId());

    long correctionId = insertCorrection(original, 9_900_000_000L, "provider restated the balance");
    cache.refresh(fundId);

    assertThat(cache.current(fundId))
        .hasValueSatisfying(
            held -> {
              assertThat(held.navVersionId()).isEqualTo(correctionId);
              assertThat(held.nav()).isEqualTo(Nav.of(9_900_000_000L, CurrencyCode.MXN));
            });
    // The original is untouched and still readable — a wrong NAV is never deleted.
    assertThat(repository.findOriginal(fundId, date))
        .map(FundNav::navVersionId)
        .contains(original.navVersionId());
  }

  @Test
  @DisplayName("the live row is the one nothing supersedes, not the one holding a null")
  void theLiveRowIsTheOneNothingSupersedes() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original = repository.strike(candidate(fundId, date, 1_000_000_000L, 1_000));
    long correctionId = insertCorrection(original, 8_500_000_000L, "restated");

    // findOriginal still returns the original — that is what it is for.
    assertThat(repository.findOriginal(fundId, date))
        .map(FundNav::navVersionId)
        .contains(original.navVersionId());
    // findLatestLive must not: filtering on supersedes_id IS NULL here would keep serving a
    // price already declared wrong, which is the failure a correction exists to prevent.
    assertThat(repository.findLatestLive(fundId)).map(FundNav::navVersionId).contains(correctionId);
  }

  @Test
  @DisplayName("a price survives a Redis round trip as exact integers")
  void aPriceSurvivesARedisRoundTripAsExactIntegers() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav struck = repository.strike(candidate(fundId, date, 1_234_567_891L, 7_777));

    snapshots.save(struck);

    assertThat(snapshots.loadAll())
        .filteredOn(row -> row.fundId().equals(fundId))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.nav().scaled()).isEqualTo(1_234_567_891L);
              assertThat(row.unitsInIssue()).isEqualTo(Units.of(7_777L * Units.SCALE));
              assertThat(row.navVersionId()).isEqualTo(struck.navVersionId());
              assertThat(row.valuationDate()).isEqualTo(date);
              assertThat(row.source()).isEqualTo("MODEL_B");
            });
  }

  /**
   * The Lua script's whole purpose. Instances sweep independently, so a slow one can try to write
   * an older version after a fast one wrote a newer — compared inside the script this is
   * impossible, compared in Java it is a lost update.
   */
  @Test
  @DisplayName("an older snapshot never overwrites a newer one")
  void anOlderSnapshotNeverOverwritesANewerOne() {
    FundId fundId = registerFund();
    FundNav newer = row(fundId, LocalDate.of(2026, 3, 3), 2_000_000_000L, 900_000_000L);
    FundNav older = row(fundId, LocalDate.of(2026, 3, 2), 1_000_000_000L, 800_000_000L);

    snapshots.save(newer);
    snapshots.save(older);

    assertThat(snapshots.loadAll())
        .filteredOn(held -> held.fundId().equals(fundId))
        .singleElement()
        .satisfies(
            held -> {
              assertThat(held.navVersionId()).isEqualTo(900_000_000L);
              assertThat(held.nav().scaled()).isEqualTo(2_000_000_000L);
            });
  }

  /** The acceptance criterion, against a Redis that genuinely is not there. */
  @Test
  @DisplayName("Redis unavailable does not stop NAV reads")
  void redisUnavailableDoesNotStopNavReads() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    repository.strike(candidate(fundId, date, 1_100_000_000L, 1_000));

    // A port nothing is listening on. Construction must not fail, because the connection is
    // opened on first use rather than eagerly -- that is what stops a pod restarting during a
    // cache outage from crash-looping.
    try (RedisNavSnapshotStore unreachable =
        new RedisNavSnapshotStore(
            clientFor("redis://127.0.0.1:1"), new RedisMetrics(meterRegistry))) {
      assertThat(unreachable.loadAll()).isEmpty();
      // Swallowed, not thrown: a warm-start write against a dead Redis is a no-op.
      unreachable.save(repository.findLatestLive(fundId).orElseThrow());

      InMemoryNavCache degraded =
          new InMemoryNavCache(repository, unreachable, clock, Duration.ofHours(26));
      assertThat(degraded.warmStart()).isZero();
      assertThat(degraded.current(fundId))
          .map(CachedNav::nav)
          .contains(Nav.of(1_100_000_000L, CurrencyCode.MXN));
      assertThat(
              meterRegistry
                  .get("redis_request_errors_total")
                  .tag("operation", "loadAll")
                  .counter()
                  .count())
          .isPositive();
      assertThat(
              meterRegistry
                  .get("redis_request_errors_total")
                  .tag("operation", "save")
                  .counter()
                  .count())
          .isPositive();
    }
  }

  /**
   * docs/ENGINEERING-RULES.md §4: a suite that only exercises rising prices will pass on a broken
   * implementation. Each day's price is struck, swept and read back, and the series is asserted to
   * genuinely fall.
   */
  @Test
  @DisplayName("a falling and volatile series converges correctly on every date")
  void aFallingAndVolatileSeriesConvergesCorrectlyOnEveryDate() {
    FundId fundId = registerFund();
    long[] series = {
      1_000_000_000L, 1_030_000_000L, 940_000_000L, 880_000_000L, 1_010_000_000L, 830_000_000L
    };
    LocalDate start = LocalDate.of(2026, 4, 1);

    List<Long> observed = new java.util.ArrayList<>();
    for (int day = 0; day < series.length; day++) {
      repository.strike(candidate(fundId, start.plusDays(day), series[day], 1_000));
      cache.refreshAll();
      observed.add(cache.current(fundId).orElseThrow().nav().scaled());
    }

    assertThat(observed)
        .containsExactly(
            1_000_000_000L,
            1_030_000_000L,
            940_000_000L,
            880_000_000L,
            1_010_000_000L,
            830_000_000L);
    assertThat(observed.get(2)).isLessThan(observed.get(1));
    assertThat(observed.getLast()).isLessThan(observed.getFirst());
  }

  private static RedisClient clientFor(String url) {
    RedisClient client = RedisClient.create(RedisURI.create(url));
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    client.setDefaultTimeout(Duration.ofMillis(250));
    return client;
  }

  /**
   * Appends a correction exactly as Task 20.5 will: a new row carrying {@code supersedes_id} and a
   * reason. Nothing is updated or deleted — the table's trigger forbids both.
   */
  private long insertCorrection(FundNav original, long navScaled, String reason) {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source,
                supersedes_id, reason, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, :unitsInIssue, 'MODEL_B',
                    :supersedesId, :reason, :now)
            RETURNING nav_version_id
            """)
        .param("fundId", original.fundId().value())
        .param("valuationDate", original.valuationDate())
        .param("navScaled", navScaled)
        .param("unitsInIssue", original.unitsInIssue().scaled())
        .param("supersedesId", original.navVersionId())
        .param("reason", reason)
        .param("now", Timestamp.from(clock.instant()))
        .query(Long.class)
        .single();
  }

  private FundNav candidate(FundId fundId, LocalDate date, long navScaled, long units) {
    return new FundNav(
        0L,
        fundId,
        date,
        Nav.of(navScaled, CurrencyCode.MXN),
        Units.of(units * Units.SCALE),
        "MODEL_B",
        null,
        null,
        clock.instant());
  }

  /** An unpersisted row, for the snapshot store's own tests — it never consults the database. */
  private FundNav row(FundId fundId, LocalDate date, long navScaled, long versionId) {
    return new FundNav(
        versionId,
        fundId,
        date,
        Nav.of(navScaled, CurrencyCode.MXN),
        Units.of(1_000L * Units.SCALE),
        "MODEL_B",
        null,
        null,
        clock.instant());
  }

  private FundId registerFund() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000))
        .param("inceptionDate", LocalDate.of(2025, 1, 1))
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", LocalDate.of(2025, 1, 1))
        .param("name", "Test fund " + fundId.value())
        .param("now", Timestamp.from(now))
        .update();
    return fundId;
  }
}
