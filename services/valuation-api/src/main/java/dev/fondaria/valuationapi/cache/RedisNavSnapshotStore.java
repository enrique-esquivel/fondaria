package dev.fondaria.valuationapi.cache;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.valuation.NavSnapshotStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NavSnapshotStore} over Redis, via Lettuce (Task 20.6).
 *
 * <p><b>Every failure is swallowed and logged.</b> Same rule as the cash-receipt cache, and here it
 * is an explicit acceptance criterion — "Redis unavailable does not stop NAV reads". Nothing in
 * this class is consulted before a decision money depends on: {@code reference.fund_nav} is the
 * price of record, and this only spares a just-started instance its first database read.
 *
 * <p><b>Integers only, and no {@code INCRBYFLOAT}</b> (docs/ENGINEERING-RULES.md §4). The NAV is
 * written as its {@code long} at scale 1e-8, units in issue likewise, and instants as epoch
 * milliseconds. Nothing here parses or writes a decimal string, so there is no point at which a
 * price could become a floating-point value.
 *
 * <p><b>The Lua script exists because there is a real read-then-write to make atomic</b> — unlike
 * the cash-receipt cache, which correctly uses two plain commands. Two things happen together here:
 *
 * <ol>
 *   <li><b>A newer snapshot must never be overwritten by an older one.</b> Instances sweep
 *       independently and a correction can land mid-sweep, so a slow instance holding version 41
 *       can try to write it after a fast one wrote version 42. Compared and rejected inside the
 *       script, that is impossible; compared in Java, it is a lost update.
 *   <li><b>The fund is registered in a set in the same breath as its hash is written</b>, because
 *       {@link #loadAll()} needs to enumerate funds and {@code SCAN} is not usable from a script. A
 *       hash written without its set entry is invisible forever.
 * </ol>
 *
 * <p>The {@code {nav}} hash tag in every key forces both into one Redis Cluster slot. v1 runs a
 * single node, so it changes nothing today and prevents a {@code CROSSSLOT} failure the day it does
 * not.
 *
 * <p>No TTL. The snapshot is one small hash per fund, rewritten by every sweep, and a fund's id is
 * never released (see {@code FundStatus.CLOSED}) — so there is nothing to expire, and an expiry
 * would only ever cost a warm start the database already covers.
 */
public class RedisNavSnapshotStore implements NavSnapshotStore {

  private static final Logger LOGGER = LoggerFactory.getLogger(RedisNavSnapshotStore.class);

  /**
   * Versioned, exactly as the cash-receipt cache's prefix is: a change to the field set below
   * cannot be misread by a running instance as though it were the old one. A new prefix simply
   * finds nothing and the cache reads PostgreSQL.
   */
  private static final String FUNDS_KEY = "nav:v1:{nav}:funds";

  private static final String FUND_KEY_PREFIX = "nav:v1:{nav}:fund:";

  /**
   * KEYS[1] the fund set · KEYS[2] this fund's hash. ARGV[1] the incoming {@code navVersionId} ·
   * ARGV[2] the fund id · ARGV[3..] field/value pairs.
   *
   * <p>Returns 1 when written, 0 when a newer or equal version was already held. {@code >=} rather
   * than {@code >} so an identical rewrite is a no-op rather than churn.
   */
  private static final String SAVE_IF_NEWER =
      """
      local incoming = tonumber(ARGV[1])
      if incoming == nil then
        return redis.error_reply('navVersionId must be an integer')
      end
      local held = redis.call('HGET', KEYS[2], 'navVersionId')
      if held then
        local current = tonumber(held)
        if current ~= nil and current >= incoming then
          return 0
        end
      end
      redis.call('DEL', KEYS[2])
      redis.call('HSET', KEYS[2], unpack(ARGV, 3))
      redis.call('SADD', KEYS[1], ARGV[2])
      return 1
      """;

  private final RedisClient client;
  private final RedisMetrics metrics;

  /**
   * Connected on first use, not in the constructor.
   *
   * <p><b>This is why a Redis that is down at start-up cannot stop the service from starting.</b>
   * {@code connect()} opens a socket, so an eager connection would make a pod restarting during a
   * cache outage crash-loop over a warm start it does not need. Deferred, the first call fails, is
   * swallowed as a miss, and the next call tries again — so Redis coming back needs no restart.
   *
   * <p>Held as a field rather than arranged with Spring's {@code @Lazy} so the guarantee belongs to
   * this class and is testable without a container.
   */
  private volatile StatefulRedisConnection<String, String> connection;

  /**
   * {@link ReentrantLock}, never {@code synchronized}: {@code connect()} is I/O
   * (docs/ENGINEERING-RULES.md §4).
   */
  private final ReentrantLock connectLock = new ReentrantLock();

  public RedisNavSnapshotStore(RedisClient client, RedisMetrics metrics) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.metrics = metrics;
  }

  @Override
  public List<FundNav> loadAll() {
    long start = System.nanoTime();
    try {
      RedisCommands<String, String> redis = connection().sync();
      List<String> fundIds = redis.smembers(FUNDS_KEY).stream().sorted().toList();
      List<FundNav> loaded = new ArrayList<>(fundIds.size());
      for (String fundId : fundIds) {
        Map<String, String> fields = redis.hgetall(FUND_KEY_PREFIX + fundId);
        if (fields.isEmpty()) {
          continue;
        }
        deserialize(fields).ifPresent(loaded::add);
      }
      metrics.requestCompleted("loadAll", System.nanoTime() - start, false);
      return List.copyOf(loaded);
    } catch (RuntimeException e) {
      // Deliberately not rethrown: a warm start is a convenience, and an unreachable
      // Redis degrades this instance to reading PostgreSQL on its first miss.
      metrics.requestCompleted("loadAll", System.nanoTime() - start, true);
      LOGGER.warn("NAV snapshot store unavailable on read; the cache will read PostgreSQL", e);
      return List.of();
    }
  }

  @Override
  public void save(FundNav nav) {
    Objects.requireNonNull(nav, "nav must not be null");
    String fundId = nav.fundId().value().toString();
    long start = System.nanoTime();
    try {
      connection()
          .sync()
          .eval(
              SAVE_IF_NEWER,
              ScriptOutputType.INTEGER,
              new String[] {FUNDS_KEY, FUND_KEY_PREFIX + fundId},
              arguments(nav, fundId));
      metrics.requestCompleted("save", System.nanoTime() - start, false);
    } catch (RuntimeException e) {
      metrics.requestCompleted("save", System.nanoTime() - start, true);
      LOGGER.warn(
          "NAV snapshot store unavailable on write; the next sweep rewrites it (fund {})",
          fundId,
          e);
    }
  }

  /** Double-checked, and correct because the field is {@code volatile}. */
  private StatefulRedisConnection<String, String> connection() {
    StatefulRedisConnection<String, String> held = connection;
    if (held != null) {
      return held;
    }
    connectLock.lock();
    try {
      if (connection == null) {
        connection = client.connect();
      }
      return connection;
    } finally {
      connectLock.unlock();
    }
  }

  /**
   * Releases the connection and the client. Failures are logged, never thrown out of a shutdown.
   */
  @Override
  public void close() {
    try {
      StatefulRedisConnection<String, String> held = connection;
      if (held != null) {
        held.close();
      }
      client.shutdown();
    } catch (RuntimeException e) {
      LOGGER.warn("NAV snapshot store did not shut down cleanly", e);
    }
  }

  /**
   * Field order is irrelevant to Redis but every value is a string, so integers are rendered with
   * {@code Long.toString} and read back with {@code Long.parseLong} — never a decimal literal that
   * something downstream could widen into a floating-point type.
   */
  private static String[] arguments(FundNav nav, String fundId) {
    List<String> argv = new ArrayList<>();
    argv.add(Long.toString(nav.navVersionId()));
    argv.add(fundId);
    argv.add("fundId");
    argv.add(fundId);
    argv.add("navVersionId");
    argv.add(Long.toString(nav.navVersionId()));
    argv.add("navScaled");
    argv.add(Long.toString(nav.nav().scaled()));
    argv.add("currency");
    argv.add(nav.nav().currency().name());
    argv.add("valuationDate");
    argv.add(nav.valuationDate().toString());
    argv.add("unitsInIssue");
    argv.add(Long.toString(nav.unitsInIssue().scaled()));
    argv.add("source");
    argv.add(nav.source());
    argv.add("publishedAtEpochMilli");
    argv.add(Long.toString(nav.publishedAt().toEpochMilli()));
    if (nav.supersedesId() != null) {
      argv.add("supersedesId");
      argv.add(Long.toString(nav.supersedesId()));
      argv.add("reason");
      argv.add(nav.reason());
    }
    return argv.toArray(String[]::new);
  }

  /**
   * A snapshot that cannot be read back is dropped, not repaired and not rethrown: the cache falls
   * through to PostgreSQL, which is the authority anyway.
   */
  private static Optional<FundNav> deserialize(Map<String, String> fields) {
    try {
      String supersedesId = fields.get("supersedesId");
      return Optional.of(
          new FundNav(
              Long.parseLong(fields.get("navVersionId")),
              new FundId(UUID.fromString(fields.get("fundId"))),
              LocalDate.parse(fields.get("valuationDate")),
              Nav.of(
                  Long.parseLong(fields.get("navScaled")),
                  CurrencyCode.valueOf(fields.get("currency"))),
              Units.of(Long.parseLong(fields.get("unitsInIssue"))),
              fields.get("source"),
              supersedesId == null ? null : Long.parseLong(supersedesId),
              fields.get("reason"),
              Instant.ofEpochMilli(Long.parseLong(fields.get("publishedAtEpochMilli")))));
    } catch (RuntimeException e) {
      LOGGER.warn("Discarding an unreadable NAV snapshot; PostgreSQL remains the authority", e);
      return Optional.empty();
    }
  }
}
