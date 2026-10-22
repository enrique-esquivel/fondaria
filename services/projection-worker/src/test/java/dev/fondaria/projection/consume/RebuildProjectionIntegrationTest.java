package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.projection.ProjectionWorkerApplication;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Task 5.8 / Task 30b2 — the projection rebuild test, and the assertion behind {@code make
 * rebuild-projection}.
 *
 * <p>The operational question this answers: <b>if the whole projection is thrown away and rebuilt
 * from the CDC log with {@code --timestamp-last=0}, is the result the same state, byte for
 * byte?</b> A "yes" is what makes {@code projection.*} disposable — it is a cache of the ledger
 * (ADR-0008), and a cache you cannot rebuild is not a cache, it is a second source of truth with no
 * reconciliation path. The operator procedure that uses this property is {@code
 * docs/RUNBOOKS.md#cdc-replay}.
 */
@Tag("integration")
class RebuildProjectionIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(RebuildProjectionIntegrationTest.class);

  /** The count Task 5.8 names ("rebuild duration for 100,000 transfers measured and recorded"). */
  private static final int DEFAULT_TRANSFER_COUNT = 100_000;

  private static final int FUNDS = 10;
  private static final int FUND_LEDGER_BASE = 1001;
  private static final int PARTICIPANTS = 2_000;
  private static final int BUSINESS_DAYS = 20;

  /** A fixed 5,000.00 MXN contribution, in cents — the numerator for units = cash ÷ NAV. */
  private static final long CONTRIBUTION_MINOR_UNITS = 500_000L;

  /** NAV of 1.00000000 at scale 1e-8 — every fund's series starts here. */
  private static final long NAV_ONE = 100_000_000L;

  private static final int VESTED = LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED.code();
  private static final int UNITS_IN_ISSUE = LedgerCodes.AccountCode.UNITS_IN_ISSUE.code();
  private static final int CONTRIBUTION_ALLOTMENT =
      LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code();

  private static final LocalDate WEEK_START_BASE = LocalDate.of(2026, 1, 5);
  private static final LocalTime NOON = LocalTime.NOON;

  /** Where the nightly job reads the measured rebuild rate from. Module-relative (surefire cwd). */
  private static final Path METRICS_FILE = Path.of("target", "rebuild-metrics.prom");

  private static int transferCount;
  private static UUID RUN_SALT;
  private static LocalDate weekStart;

  private static PostgresFixture pg;
  private static RabbitMqFixture rabbit;

  private static String transfersTopic;
  private static String entriesTopic;
  private static String deadLetterTopic;
  private static String transfersQueue;
  private static String entriesQueuePrefix;

  @BeforeAll
  static void setUp() {
    pg = PostgresFixture.getInstance();
    rabbit = RabbitMqFixture.getInstance();
    transferCount = Integer.getInteger("rebuild.transferCount", DEFAULT_TRANSFER_COUNT);
    RUN_SALT = UUID.randomUUID();
    weekStart = WEEK_START_BASE.plusWeeks(Math.floorMod(RUN_SALT.getMostSignificantBits(), 520));

    String nonce = UUID.randomUUID().toString().substring(0, 8);
    transfersTopic = "test.rebuild.transfers." + nonce;
    entriesTopic = "test.rebuild.entries." + nonce;
    deadLetterTopic = "test.rebuild.dlq." + nonce;
    transfersQueue = "test.rebuild.proj.transfers." + nonce;
    entriesQueuePrefix = "test.rebuild.proj.entries." + nonce;
    createTopology();
  }

  @AfterAll
  static void tidyTopics() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.queueDelete(transfersQueue);
      for (int i = 0; i < 16; i++) {
        channel.queueDelete(entriesQueuePrefix + "." + i);
      }
      channel.queueDelete(deadLetterTopic);
      channel.exchangeDelete(transfersTopic);
      channel.exchangeDelete(entriesTopic);
    } catch (Exception ignored) {
    }
  }

  @Test
  @DisplayName(
      "truncate + replay from CDC timestamp-last=0 rebuilds byte-identical projection state")
  void rebuildFromZeroProducesIdenticalState() {
    Run run = generate();

    // The NAV series genuinely falls — for every fund, below where it started (AGENTS.md §4).
    for (int f = 0; f < FUNDS; f++) {
      assertThat(navSeriesFalls(run.nav[f]))
          .as("fund %d NAV series must fall below its opening price", f)
          .isTrue();
    }

    produce(run.stream);

    // The checkpoint's high-water mark (MAX over every consumer's row, ADR-0032) is shared across
    // this JVM's tests; a previous one may have advanced it past our window. Reset every row so
    // both the build and the rebuild measure from 0 and the checkpoint assertion is meaningful.
    JdbcClient admin = ownerJdbc();
    resetCheckpoint(admin);

    // ---- Build -------------------------------------------------------------
    Snapshot built;
    try (ConfigurableApplicationContext ctx = bootWorker("projection-rebuild-build-" + shortId())) {
      JdbcClient jdbc = ctx.getBean(JdbcClient.class);
      awaitConvergence(jdbc, run, "build");
      built = snapshot(jdbc, run.tenant);
    }
    assertThat(checkpoint(admin))
        .as("build: the checkpoint reached exactly the last produced timestamp")
        .isEqualTo(run.maxTimestamp);
    assertThat(built.transfers).as("build produced transfer rows").isNotEmpty();
    assertThat(built.holdings).as("build produced holding rows").isNotEmpty();
    assertThat(built.transfers).hasSize((int) run.distinctTransfers());
    assertThat(built.holdings).hasSize(run.holdingRowKeys.size());

    // ---- Rebuild ----------------------------------------------------------
    truncateProjection(admin);
    assertThat(rowCount(admin, "projection.transfer")).isZero();
    assertThat(rowCount(admin, "projection.participant_units")).isZero();

    produce(run.stream);

    Snapshot rebuilt;
    Duration rebuildTime;
    try (ConfigurableApplicationContext ctx =
        bootWorker("projection-rebuild-replay-" + shortId())) {
      JdbcClient jdbc = ctx.getBean(JdbcClient.class);
      // Measure a running worker draining the whole log from CDC replay — not the JVM/Spring boot,
      // which the operational rebuild (see the runbook) does not incur.
      long start = System.nanoTime();
      awaitConvergence(jdbc, run, "rebuild");
      rebuildTime = Duration.ofNanos(System.nanoTime() - start);
      rebuilt = snapshot(jdbc, run.tenant);
    }

    // ---- Assert ----------------------------------------------------------
    assertThat(rebuilt.transfers)
        .as("projection.transfer must be identical after a rebuild from CDC timestamp-last=0")
        .isEqualTo(built.transfers);
    assertThat(rebuilt.holdings)
        .as(
            "projection.participant_units (row_hash chain included) must be identical after rebuild")
        .isEqualTo(built.holdings);
    assertThat(checkpoint(admin))
        .as("rebuild: replaying from 0 caught the checkpoint back up to exactly the last timestamp")
        .isEqualTo(run.maxTimestamp);

    recordRate(rebuildTime, run.distinctTransfers());
  }

  // ---------------------------------------------------------------------------
  // the rebuild rate — Task 5.8 AC "rate recorded so regressions are visible"
  // ---------------------------------------------------------------------------

  private static void recordRate(Duration rebuildTime, long transfers) {
    double seconds = rebuildTime.toNanos() / 1_000_000_000.0;
    double perSecond = seconds > 0 ? transfers / seconds : 0.0;
    String prom =
        String.format(
            Locale.ROOT,
            """
            # Task 5.8 / Task 30b2 — projection rebuild from CDC timestamp-last=0. Written by RebuildProjectionIntegrationTest.
            # HELP projection_rebuild_duration_seconds Wall-clock time to truncate and rebuild the projection from timestamp-last=0.
            # TYPE projection_rebuild_duration_seconds gauge
            projection_rebuild_duration_seconds %.3f
            # HELP projection_rebuild_transfers_total Distinct transfers replayed in the measured rebuild.
            # TYPE projection_rebuild_transfers_total gauge
            projection_rebuild_transfers_total %d
            # HELP projection_rebuild_transfers_per_second Rebuild throughput; a regression here shows as a drop.
            # TYPE projection_rebuild_transfers_per_second gauge
            projection_rebuild_transfers_per_second %.1f
            """,
            seconds,
            transfers,
            perSecond);
    try {
      Files.createDirectories(METRICS_FILE.getParent());
      Files.writeString(METRICS_FILE, prom);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    LOGGER.info(
        "projection rebuild complete: {\"event\":\"projection_rebuild_complete\","
            + "\"transfers\":{},\"duration_seconds\":{},\"transfers_per_second\":{}}",
        transfers,
        String.format(Locale.ROOT, "%.3f", seconds),
        String.format(Locale.ROOT, "%.1f", perSecond));
    System.out.print(prom);
  }

  // ---------------------------------------------------------------------------
  // the snapshot — every column that must survive a rebuild unchanged
  // ---------------------------------------------------------------------------

  private record Snapshot(
      List<Map<String, Object>> transfers, List<Map<String, Object>> holdings) {}

  private static Snapshot snapshot(JdbcClient jdbc, String tenant) {
    List<Map<String, Object>> transfers =
        jdbc.sql(
                """
                SELECT transfer_id, ledger_timestamp, ledger_id, debit_account_id,
                       credit_account_id, amount_scaled, code, booking_date, value_date,
                       nav_version_id, reference
                  FROM projection.transfer
                 WHERE tenant_id = :t
                 ORDER BY transfer_id, value_date
                """)
            .param("t", tenant)
            .query()
            .listOfRows();
    List<Map<String, Object>> holdings =
        jdbc.sql(
                """
                SELECT participant_id, fund_id, effective_date, units_scaled, last_transfer_id,
                       last_ledger_timestamp,
                       encode(prev_row_hash, 'hex') AS prev_row_hash,
                       encode(row_hash, 'hex')      AS row_hash
                  FROM projection.participant_units
                 WHERE tenant_id = :t
                 ORDER BY participant_id, fund_id, effective_date
                """)
            .param("t", tenant)
            .query()
            .listOfRows();
    return new Snapshot(transfers, holdings);
  }

  private static void truncateProjection(JdbcClient admin) {
    // TRUNCATE, never DELETE: V21's append-only triggers raise on DELETE for every role, the
    // owner included, but do not fire on TRUNCATE. This is the rebuild path the migration's own
    // comment reserves for Task 5.8.
    admin.sql("TRUNCATE projection.transfer, projection.participant_units").update();
    resetCheckpoint(admin);
  }

  private static void resetCheckpoint(JdbcClient admin) {
    admin
        .sql("DELETE FROM projection.projection_consumer_checkpoint WHERE projection_name = :n")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .update();
  }

  private static long rowCount(JdbcClient jdbc, String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  /**
   * A {@link JdbcClient} on the fixture's owner role. The Testcontainers login role is the database
   * owner, so it can {@code TRUNCATE}; the runtime {@code fondaria_cdc}/{@code fondaria_app} roles
   * cannot (V21). Independent of any worker context's lifecycle.
   */
  private static JdbcClient ownerJdbc() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    ds.setDriverClassName("org.postgresql.Driver");
    return JdbcClient.create(ds);
  }

  // ---------------------------------------------------------------------------
  // convergence — when has the worker consumed the whole backlog?
  // ---------------------------------------------------------------------------

  private static void awaitConvergence(JdbcClient jdbc, Run run, String phase) {
    await()
        .atMost(Duration.ofMinutes(30))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              assertThat(transferRowCount(jdbc, run.tenant))
                  .as("%s: transfer rows", phase)
                  .isEqualTo(run.distinctTransfers());
              assertThat(holdingRowCount(jdbc, run.tenant))
                  .as("%s: holding rows", phase)
                  .isEqualTo((long) run.holdingRowKeys.size());
              assertThat(sumAllLatestUnits(jdbc, run.tenant))
                  .as("%s: Σ latest units == Σ units in issue", phase)
                  .isEqualTo(run.totalUnitsInIssue());
              assertThat(checkpoint(jdbc))
                  .as("%s: checkpoint reached the last produced timestamp", phase)
                  .isGreaterThanOrEqualTo(run.maxTimestamp);
            });
  }

  private static long transferRowCount(JdbcClient jdbc, String tenant) {
    return jdbc.sql("SELECT count(*) FROM projection.transfer WHERE tenant_id = :t")
        .param("t", tenant)
        .query(Long.class)
        .single();
  }

  private static long holdingRowCount(JdbcClient jdbc, String tenant) {
    return jdbc.sql("SELECT count(*) FROM projection.participant_units WHERE tenant_id = :t")
        .param("t", tenant)
        .query(Long.class)
        .single();
  }

  private static long sumAllLatestUnits(JdbcClient jdbc, String tenant) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(u.units_scaled), 0) FROM ("
                + "  SELECT DISTINCT ON (participant_id, fund_id) units_scaled"
                + "  FROM projection.participant_units"
                + "  WHERE tenant_id = :t"
                + "  ORDER BY participant_id, fund_id, effective_date DESC) u")
        .param("t", tenant)
        .query(Long.class)
        .single();
  }

  private static BigInteger checkpoint(JdbcClient jdbc) {
    return jdbc.sql(
            "SELECT max(last_ledger_timestamp) FROM projection.projection_consumer_checkpoint"
                + " WHERE projection_name = :n")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .query(BigInteger.class)
        .optional()
        .orElse(BigInteger.valueOf(-1));
  }

  // ---------------------------------------------------------------------------
  // the generated run
  // ---------------------------------------------------------------------------

  private static final class Run {
    final String tenant;
    final long[][] nav = new long[FUNDS][BUSINESS_DAYS];
    final List<Msg> stream = new ArrayList<>();
    final long[] unitsInIssue = new long[FUNDS];
    final Set<BigInteger> transferIds = new HashSet<>();
    final Set<String> holdingRowKeys = new HashSet<>();

    /** Holding account id → its running unit balance, carried as {@code creditsPosted}. */
    final Map<BigInteger, Long> holdingBalance = new LinkedHashMap<>();

    BigInteger maxTimestamp = BigInteger.ZERO;

    Run(String tenant) {
      this.tenant = tenant;
    }

    long distinctTransfers() {
      return transferIds.size();
    }

    long totalUnitsInIssue() {
      long total = 0;
      for (long units : unitsInIssue) {
        total += units;
      }
      return total;
    }
  }

  private record Msg(String topic, String key, String body) {}

  private static Run generate() {
    long seed = RUN_SALT.getMostSignificantBits() ^ RUN_SALT.getLeastSignificantBits();
    Random rng = new Random(seed);
    Random idRng = new Random(seed * 1_099_511_628_211L ^ 0x5DEECE66DL);
    Run run =
        new Run(
            TenantRef.derive(RUN_SALT, new OrganizationId(new UUID(seed, ~seed)))
                .asUnsignedDecimal());

    for (int f = 0; f < FUNDS; f++) {
      run.nav[f] = navSeries(rng);
    }

    Map<Long, BigInteger> accounts = new HashMap<>();
    List<Msg> base = new ArrayList<>();
    long tsCounter = 0;

    for (int i = 0; i < transferCount; i++) {
      int day = (int) ((long) i * BUSINESS_DAYS / transferCount);
      int participant = rng.nextInt(PARTICIPANTS);
      int fund = rng.nextInt(FUNDS);
      int fundLedger = FUND_LEDGER_BASE + fund;

      long navScaled = run.nav[fund][day];
      long delta = CONTRIBUTION_MINOR_UNITS * NAV_ONE / navScaled;
      if (delta <= 0) {
        throw new IllegalStateException("non-positive unit delta from NAV " + navScaled);
      }

      BigInteger account =
          accounts.computeIfAbsent((long) participant * FUNDS + fund, k -> freshId(idRng));
      run.holdingRowKeys.add(account + "|" + businessDate(day));
      BigInteger transferId = freshId(idRng);
      run.transferIds.add(transferId);

      long balance =
          run.holdingBalance.merge(account, delta, Long::sum); // running balance for creditsPosted
      run.unitsInIssue[fund] += delta;

      String ts = timestamp(day, tsCounter++);
      run.maxTimestamp = run.maxTimestamp.max(new BigInteger(ts));

      base.add(
          new Msg(
              transfersTopic,
              transferId.toString(),
              transferJson(
                  fundLedger, ts, transferId, delta, run.tenant, freshId(idRng), account)));
      base.add(
          new Msg(
              entriesTopic,
              account.toString(),
              entryJson(fundLedger, ts, transferId, VESTED, account, run.tenant, balance)));
    }

    // Non-holding legs the projection must ignore without dead-lettering and without touching
    // participant_units: one UNITS_IN_ISSUE leg and one cash leg per fund.
    for (int f = 0; f < FUNDS; f++) {
      String ts = timestamp(0, tsCounter++);
      run.maxTimestamp = run.maxTimestamp.max(new BigInteger(ts));
      base.add(
          new Msg(
              entriesTopic,
              freshId(idRng).toString(),
              entryJson(
                  FUND_LEDGER_BASE + f,
                  ts,
                  freshId(idRng),
                  UNITS_IN_ISSUE,
                  freshId(idRng),
                  run.tenant,
                  999_000L)));
      base.add(
          new Msg(
              entriesTopic,
              freshId(idRng).toString(),
              cashEntryJson(ts, freshId(idRng), freshId(idRng), run.tenant)));
    }

    // "Assume every message arrives more than once" (AGENTS.md §4): redeliver a ~12% sample at a
    // later offset than the original, so a redelivery lands out of ledger-timestamp order — the
    // exact thing the rebuild must absorb to the same state. Base slot k has zero low bits
    // (k << 20); a redelivery carries non-zero jitter, so it can never overwrite an original.
    int slotBits = 20;
    long slotSize = 1L << slotBits;
    TreeMap<Long, Msg> byRelease = new TreeMap<>();
    for (int k = 0; k < base.size(); k++) {
      byRelease.put((long) k << slotBits, base.get(k));
    }
    Random dupRng = new Random(seed * 31 + 7);
    for (int k = 0; k < base.size(); k++) {
      if (dupRng.nextInt(100) < 12) {
        int target = Math.min(k + 1 + dupRng.nextInt(400), base.size() - 1);
        byRelease.put(
            ((long) target << slotBits) + 1 + dupRng.nextInt((int) slotSize - 1), base.get(k));
      }
    }
    run.stream.addAll(byRelease.values());
    return run;
  }

  private static long[] navSeries(Random rng) {
    int[] steps = {-60, -35, -15, 10, 25, 45};
    long[] nav = new long[BUSINESS_DAYS];
    nav[0] = NAV_ONE;
    int shockDay = 1 + rng.nextInt(BUSINESS_DAYS - 1);
    for (int d = 1; d < BUSINESS_DAYS; d++) {
      if (d == shockDay) {
        nav[d] = Math.min(nav[d - 1], NAV_ONE) * 65 / 100;
      } else {
        long next = nav[d - 1] + nav[d - 1] * steps[rng.nextInt(steps.length)] / 1000;
        nav[d] = Math.max(next, 1_000L);
      }
    }
    return nav;
  }

  private static boolean navSeriesFalls(long[] nav) {
    boolean hasDownStep = false;
    long min = nav[0];
    for (int d = 1; d < nav.length; d++) {
      hasDownStep |= nav[d] < nav[d - 1];
      min = Math.min(min, nav[d]);
    }
    return hasDownStep && min < nav[0];
  }

  // ---------------------------------------------------------------------------
  // worker / broker plumbing
  // ---------------------------------------------------------------------------

  private static ConfigurableApplicationContext bootWorker(String workerSuffix) {
    Map<String, Object> props = new HashMap<>();
    props.put("PROJECTION_AMQP_HOST", rabbit.getHost());
    props.put("PROJECTION_AMQP_PORT", rabbit.getPort());
    props.put("PROJECTION_AMQP_USERNAME", rabbit.getUsername());
    props.put("PROJECTION_AMQP_PASSWORD", rabbit.getPassword());
    props.put("PROJECTION_TRANSFERS_EXCHANGE", transfersTopic);
    props.put("PROJECTION_ENTRIES_EXCHANGE", entriesTopic);
    props.put("PROJECTION_DEAD_LETTER_QUEUE", deadLetterTopic);
    props.put("PROJECTION_TRANSFERS_QUEUE", transfersQueue);
    props.put("PROJECTION_ENTRIES_QUEUE_PREFIX", entriesQueuePrefix);
    props.put("PROJECTION_ENTRIES_QUEUE_COUNT", "16");
    // A halt is a row in the reused database: a fresh name per context means one this run
    // raises can never stop a later run's worker (ADR-0032 §2).
    props.put("fondaria.projection.halt.projection-name", "test-" + java.util.UUID.randomUUID());
    props.put("POSTGRES_URL", pg.getJdbcUrl());
    props.put("POSTGRES_USER", pg.getUsername());
    props.put("POSTGRES_PASSWORD", pg.getPassword());
    props.put("PROJECTION_PREFETCH", "500");
    props.put("PROJECTION_RETRY_INITIAL_BACKOFF", "50ms");
    props.put("PROJECTION_RETRY_MAX_BACKOFF", "250ms");
    props.put("server.port", "0");
    props.put("spring.main.banner-mode", "off");
    return new SpringApplicationBuilder(ProjectionWorkerApplication.class).properties(props).run();
  }

  private static void createTopology() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      channel.exchangeDeclare(transfersTopic, "fanout", true);
      channel.exchangeDeclare(entriesTopic, "x-consistent-hash", true, false, Map.of());
      channel.queueDeclare(deadLetterTopic, true, false, false, null);
      channel.queueDeclare(transfersQueue, true, false, false, null);
      channel.queueBind(transfersQueue, transfersTopic, "");
      for (int i = 0; i < 16; i++) {
        String q = entriesQueuePrefix + "." + i;
        channel.queueDeclare(q, true, false, false, null);
        channel.queueBind(q, entriesTopic, "10");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void produce(List<Msg> stream) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      for (Msg m : stream) {
        channel.basicPublish(
            m.topic(),
            m.key(),
            MessageProperties.PERSISTENT_TEXT_PLAIN,
            m.body().getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // deterministic id / timestamp / JSON helpers
  // ---------------------------------------------------------------------------

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static BigInteger freshId(Random rng) {
    return new BigInteger(96, rng).add(BigInteger.ONE);
  }

  private static LocalDate businessDate(int day) {
    LocalDate date = weekStart;
    int added = 0;
    while (added < day) {
      date = date.plusDays(1);
      if (date.getDayOfWeek().getValue() < 6) {
        added++;
      }
    }
    return date;
  }

  private static String timestamp(int day, long seq) {
    long epochSeconds = businessDate(day).atTime(NOON).toEpochSecond(ZoneOffset.UTC);
    return BigInteger.valueOf(epochSeconds)
        .multiply(BigInteger.valueOf(1_000_000_000L))
        .add(BigInteger.valueOf(seq))
        .toString();
  }

  private static String transferJson(
      long ledger,
      String ts,
      BigInteger transferId,
      long amountScaled,
      String tenant,
      BigInteger debitAccount,
      BigInteger creditAccount) {
    return "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":"
        + ledger
        + ",\"timestamp\":\""
        + ts
        + "\",\"transferId\":\""
        + transferId
        + "\",\"amount\":\""
        + amountScaled
        + "\",\"pendingId\":\"0\",\"transferCode\":"
        + CONTRIBUTION_ALLOTMENT
        + ",\"transferFlags\":0,\"transferTimestamp\":\""
        + ts
        + "\",\"userData128\":\"0\",\"userData64\":\""
        + tenant
        + "\",\"userData32\":0,\"debitAccountId\":\""
        + debitAccount
        + "\",\"creditAccountId\":\""
        + creditAccount
        + "\",\"debitAccountCode\":"
        + UNITS_IN_ISSUE
        + ",\"creditAccountCode\":"
        + VESTED
        + "}";
  }

  private static String entryJson(
      long ledger,
      String ts,
      BigInteger transferId,
      int accountCode,
      BigInteger accountId,
      String tenant,
      long creditsPosted) {
    return "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":"
        + ledger
        + ",\"timestamp\":\""
        + ts
        + "\",\"transferId\":\""
        + transferId
        + "\",\"transferCode\":"
        + CONTRIBUTION_ALLOTMENT
        + ",\"transferTimestamp\":\""
        + ts
        + "\",\"amount\":\"1\",\"direction\":\"CREDIT\",\"accountId\":\""
        + accountId
        + "\",\"accountCode\":"
        + accountCode
        + ",\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":\"0\""
        + ",\"creditsPending\":\"0\",\"creditsPosted\":\""
        + creditsPosted
        + "\",\"accountUserData128\":\"0\",\"accountUserData64\":\""
        + tenant
        + "\",\"accountUserData32\":0,\"pendingId\":\"0\"}";
  }

  private static String cashEntryJson(
      String ts, BigInteger transferId, BigInteger accountId, String tenant) {
    return "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":484,\"timestamp\":\""
        + ts
        + "\",\"transferId\":\""
        + transferId
        + "\",\"transferCode\":1,\"transferTimestamp\":\""
        + ts
        + "\",\"amount\":\"125000\",\"direction\":\"DEBIT\",\"accountId\":\""
        + accountId
        + "\",\"accountCode\":5,\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":"
        + "\"125000\",\"creditsPending\":\"0\",\"creditsPosted\":\"0\",\"accountUserData128\":\"0\","
        + "\"accountUserData64\":\""
        + tenant
        + "\",\"accountUserData32\":0,\"pendingId\":\"0\"}";
  }
}
