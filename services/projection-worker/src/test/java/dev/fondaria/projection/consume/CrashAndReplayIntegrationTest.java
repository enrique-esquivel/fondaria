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
import dev.fondaria.ledger.LedgerEntryEvent;
import dev.fondaria.ledger.LedgerTransferEvent;
import dev.fondaria.projection.ProjectionWorkerApplication;
import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.RabbitMqFixture;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 5.7 — the crash-and-replay harness.
 *
 * <p>For each of ten independently seeded runs it publishes 5,000 contributions across ten funds,
 * priced by a <b>volatile NAV series that genuinely falls</b> (asserted), kills the worker at
 * twenty randomised points while it drains the backlog, and then asserts the four convergence
 * invariants:
 *
 * <ol>
 *   <li>{@code projection.transfer} holds exactly one row per distinct transfer;
 *   <li>every participant's {@code participant_units} holding equals the ledger's unit balance;
 *   <li>{@code Σ participant units == units in issue}, per fund;
 *   <li>zero dead-letter rows (and the worker never halted).
 * </ol>
 *
 * <p>As a fifth check — promised by {@link ParticipantUnitsProjection}'s javadoc — the per {@code
 * (participant, fund)} SHA-256 hash chain is recomputed from the stored rows for every account the
 * run created.
 *
 * <h2>What "kill the worker" means here</h2>
 *
 * <p>A kill is a fault injected at the projection's atomicity boundary: a {@link
 * FaultInjectingSink} wraps the real {@link JdbcProjectionSink} and, at a randomised delivery
 * ordinal, throws either <i>before</i> the delegate runs (nothing committed → the record is
 * re-sought and replayed) or <i>after</i> it commits but before {@link LedgerEventListener}
 * acknowledges the AMQP message (the exact window a {@code kill -9} opens — the row is durable, the
 * acknowledgement is not, so the record is redelivered and must be absorbed idempotently). This
 * drives the same at-least-once replay a process crash would, without a multi-second consumer-group
 * rebalance per kill; the genuine process-restart path (clean shutdown loses nothing, a fresh
 * instance resumes from unacknowledged messages) is already covered by {@link
 * LedgerEventListenerIntegrationTest}.
 *
 * <h2>Why it fails if the idempotency is weakened</h2>
 *
 * <ul>
 *   <li><b>Remove {@code ON CONFLICT (...) DO NOTHING} from the transfer insert</b> → a redelivered
 *       transfer (every run produces duplicates) raises a duplicate-key violation, which the error
 *       handler treats as transient and retries forever; the run never converges and the await in
 *       {@link #crashAndReplayConvergesForEverySeed()} times out.
 *   <li><b>Remove the {@code EXCLUDED.last_ledger_timestamp > ...} guard from the {@code
 *       participant_units} upsert</b> → a stale entry (the run deliberately redelivers the first
 *       contribution of a same-day group <i>after</i> the later ones) overwrites the holding with
 *       an older, smaller balance; invariant&nbsp;2 then fails for that account.
 * </ul>
 *
 * <p>Fixtures are reused across runs (AGENTS.md §4). The <i>seed</i> fixes only the shape of a run
 * — the NAV walk, which participant contributes to which fund on which day, where the kills land —
 * so a run is reproducible from its seed. Every <i>identifier</i> (tenant, holding accounts,
 * transfer ids) is salted with a per-execution {@link #RUN_SALT} so a re-run of the same seed
 * cannot collide with rows an earlier execution left in the reused database, and every assertion is
 * scoped to a {@code tenant_id} this execution created — never a count over a whole table. The date
 * window is salted too, so the checkpoint high-water mark genuinely climbs (and is still asserted
 * with {@code >=}).
 */
@Tag("integration")
class CrashAndReplayIntegrationTest {

  /** Ten runs, ten seeds (AGENTS.md — a suite that only exercises one path passes on a bug). */
  private static final long[] SEEDS = {1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L};

  private static final int CONTRIBUTIONS_PER_RUN = 5_000;
  private static final int FUNDS = 10;
  private static final int FUND_LEDGER_BASE = 1001;
  private static final int PARTICIPANTS = 300;
  private static final int BUSINESS_DAYS = 20;
  private static final int KILLS_PER_RUN = 20;

  /** A fixed 5,000.00 MXN contribution, in cents — the numerator for units = cash ÷ NAV. */
  private static final long CONTRIBUTION_MINOR_UNITS = 500_000L;

  /** NAV of 1.00000000 at scale 1e-8 — every fund's series starts here. */
  private static final long NAV_ONE = 100_000_000L;

  private static final int VESTED = LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED.code();
  private static final int UNITS_IN_ISSUE = LedgerCodes.AccountCode.UNITS_IN_ISSUE.code();
  private static final int CONTRIBUTION_ALLOTMENT =
      LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code();

  /**
   * A Monday (noon UTC = 06:00 America/Mexico_City, before the 18:00 cutoff, so the business date
   * is simply that calendar day). {@link #weekStart} is this salted forward per execution; weekends
   * are skipped when stepping day to day.
   */
  private static final LocalDate WEEK_START_BASE = LocalDate.of(2026, 1, 5);

  private static final LocalTime NOON = LocalTime.NOON;

  /**
   * Salts every identifier and the date window, so a re-run of a seed cannot collide with rows an
   * earlier execution left in the reused database.
   */
  private static UUID RUN_SALT;

  /**
   * {@link #WEEK_START_BASE} plus a salted number of whole weeks — so every execution books into a
   * fresh window and the checkpoint high-water mark genuinely advances to this execution's
   * timestamps.
   */
  private static LocalDate weekStart;

  private static RabbitMqFixture rabbit;

  private static String transfersTopic;
  private static String entriesTopic;
  private static String deadLetterTopic;
  private static String transfersQueue;
  private static String entriesQueuePrefix;
  private static ConfigurableApplicationContext context;

  @BeforeAll
  static void startWorker() {
    PostgresFixture pg = PostgresFixture.getInstance();
    rabbit = RabbitMqFixture.getInstance();
    RUN_SALT = UUID.randomUUID();
    weekStart = WEEK_START_BASE.plusWeeks(Math.floorMod(RUN_SALT.getMostSignificantBits(), 520));

    String nonce = UUID.randomUUID().toString().substring(0, 8);
    transfersTopic = "test.transfers." + nonce;
    entriesTopic = "test.entries." + nonce;
    deadLetterTopic = "test.dlq." + nonce;
    transfersQueue = "test.proj.transfers." + nonce;
    entriesQueuePrefix = "test.proj.entries." + nonce;
    createTopology();

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
    props.put("PROJECTION_PREFETCH", "200");
    props.put("PROJECTION_RETRY_INITIAL_BACKOFF", "50ms");
    props.put("PROJECTION_RETRY_MAX_BACKOFF", "250ms");
    props.put("server.port", "0");
    props.put("spring.main.banner-mode", "off");
    context =
        new SpringApplicationBuilder(ProjectionWorkerApplication.class, FaultConfig.class)
            .properties(props)
            .run();
  }

  @AfterAll
  static void stopWorker() {
    if (context != null) {
      context.close();
      context = null;
    }
  }

  @Test
  @DisplayName("ten seeded 5,000-contribution runs, worker killed 20× each, all converge exactly")
  void crashAndReplayConvergesForEverySeed() {
    JdbcClient jdbc = context.getBean(JdbcClient.class);
    FaultInjectingSink fault = context.getBean(FaultInjectingSink.class);
    ProjectionHalt halt = context.getBean(ProjectionHalt.class);

    boolean someRunHadAFallingFund = false;

    for (long seed : SEEDS) {
      Run run = generate(seed);

      // AC: the NAV series genuinely falls — for every fund, at some point, below where it started.
      for (int f = 0; f < FUNDS; f++) {
        assertThat(navSeriesFalls(run.nav[f]))
            .as("fund %d NAV series (seed %d) must fall below its opening price", f, seed)
            .isTrue();
      }
      someRunHadAFallingFund = true;

      long deliveries = run.stream.size();
      fault.arm(seed, deliveries, KILLS_PER_RUN);

      produce(run.stream);

      // Convergence: BOTH topics fully drained. The transfers topic and the entries topic drain
      // independently, and the checkpoint high-water mark (MAX over every consumer) can reach
      // maxTimestamp while entry
      // messages are still in flight — so the entry side needs its own signals: the exact sparse
      // row count, and Σ latest-units == Σ units in issue.
      await()
          .atMost(Duration.ofMinutes(4))
          .pollInterval(Duration.ofMillis(500))
          .untilAsserted(
              () -> {
                assertThat(halt.isHalted())
                    .as("worker halted mid-run (seed %d): %s", seed, halt.reason().orElse(""))
                    .isFalse();
                assertThat(transferRowCount(jdbc, run.tenant)).isEqualTo(run.distinctTransfers());
                assertThat(holdingRowCount(jdbc, run.tenant))
                    .isEqualTo((long) run.holdingRowKeys.size());
                assertThat(sumAllLatestUnits(jdbc, run.tenant)).isEqualTo(run.totalUnitsInIssue());
                assertThat(checkpoint(jdbc)).isGreaterThanOrEqualTo(run.maxTimestamp);
              });

      assertThat(fault.firedCount())
          .as("the worker was actually killed %d times in run (seed %d)", KILLS_PER_RUN, seed)
          .isEqualTo(KILLS_PER_RUN);

      // 1 — one row per distinct transfer, nothing double-counted.
      assertThat(transferRowCount(jdbc, run.tenant)).isEqualTo(run.distinctTransfers());
      assertThat(distinctTransferIds(jdbc, run.tenant)).isEqualTo(run.distinctTransfers());

      // 2 — every participant holding equals the (modelled) ledger unit balance.
      run.ledgerUnits.forEach(
          (account, balance) ->
              assertThat(latestUnits(jdbc, run.tenant, account))
                  .as("holding for account %s (seed %d)", account, seed)
                  .isEqualTo(balance));

      // 3 — Σ participant units == units in issue, per fund.
      for (int f = 0; f < FUNDS; f++) {
        assertThat(sumLatestUnits(jdbc, run.tenant, FUND_LEDGER_BASE + f))
            .as("Σ units for fund %d (seed %d)", f, seed)
            .isEqualTo(run.unitsInIssue[f]);
      }

      // 4 — nothing dead-lettered, worker never halted.
      assertThat(deadLetterRecordCount()).isZero();
      assertThat(halt.isHalted()).isFalse();

      // 5 — the per-(participant,fund) hash chain recomputes from the stored rows.
      run.ledgerUnits.keySet().forEach(account -> assertHashChain(jdbc, run.tenant, account, run));

      fault.disarm();
    }

    assertThat(someRunHadAFallingFund).isTrue();
  }

  // ---------------------------------------------------------------------------
  // the generated run
  // ---------------------------------------------------------------------------

  /** Everything one seeded run produces, plus the model the projection is checked against. */
  private static final class Run {
    final String tenant;
    final long[][] nav = new long[FUNDS][BUSINESS_DAYS];
    final List<Msg> stream = new ArrayList<>();

    /** holding account id -> its final cumulative units (the "ledger balance"). */
    final Map<BigInteger, Long> ledgerUnits = new LinkedHashMap<>();

    /** holding account id -> its fund's ledger id, for the hash-chain recomputation. */
    final Map<BigInteger, Integer> accountFund = new HashMap<>();

    final long[] unitsInIssue = new long[FUNDS];
    final Set<BigInteger> transferIds = new HashSet<>();

    /** distinct {@code account|effective_date} pairs — the exact number of sparse holding rows. */
    final Set<String> holdingRowKeys = new HashSet<>();

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

  /** One record to produce: which topic, its key, and its JSON body. */
  private record Msg(String topic, String key, String body) {}

  private static Run generate(long seed) {
    Random rng = new Random(seed); // the run's shape — reproducible from the seed alone
    Random idRng =
        new Random(
            seed * 1_099_511_628_211L
                ^ RUN_SALT.getMostSignificantBits()
                ^ (RUN_SALT.getLeastSignificantBits() * 31)); // identifiers — unique per execution
    Run run =
        new Run(
            TenantRef.derive(
                    RUN_SALT, new OrganizationId(new UUID(seed, ~seed ^ RUN_SALT.hashCode())))
                .asUnsignedDecimal());

    for (int f = 0; f < FUNDS; f++) {
      run.nav[f] = navSeries(rng);
    }

    // A holding account id per (participant, fund), created lazily in first-touch order.
    Map<Long, BigInteger> accounts = new HashMap<>();

    // Per (participant, fund, day): the ordinals in `base` of that holding account's entry messages
    // on that day, so the first entry of a same-day group can be redelivered after the group is
    // complete — the stale redelivery 5.5's timestamp guard exists to absorb.
    Map<Long, List<Integer>> sameDayEntryOrdinals = new HashMap<>();
    List<Msg> base = new ArrayList<>();
    long lastParticipant = -1;
    long lastFund = -1;
    long lastDay = -1;

    long tsCounter = 0;
    for (int i = 0; i < CONTRIBUTIONS_PER_RUN; i++) {
      int day = (int) ((long) i * BUSINESS_DAYS / CONTRIBUTIONS_PER_RUN);

      int participant;
      int fund;
      if (i % 5 == 0 && lastDay == day && lastParticipant >= 0) {
        // Deliberately a second contribution for the same (participant, fund) on the same day —
        // this is the row a stale redelivery would corrupt without 5.5's timestamp guard.
        participant = (int) lastParticipant;
        fund = (int) lastFund;
      } else {
        participant = rng.nextInt(PARTICIPANTS);
        fund = rng.nextInt(FUNDS);
      }
      lastParticipant = participant;
      lastFund = fund;
      lastDay = day;

      int fundLedger = FUND_LEDGER_BASE + fund;
      long navScaled = run.nav[fund][day];
      long delta = CONTRIBUTION_MINOR_UNITS * NAV_ONE / navScaled;
      if (delta <= 0) {
        throw new IllegalStateException("non-positive unit delta from NAV " + navScaled);
      }

      BigInteger account =
          accounts.computeIfAbsent((long) participant * FUNDS + fund, k -> freshId(idRng));
      run.accountFund.put(account, fundLedger);
      run.holdingRowKeys.add(account + "|" + businessDate(day));
      BigInteger unitsInIssueAccount = freshId(idRng);
      BigInteger transferId = freshId(idRng);
      run.transferIds.add(transferId);

      long balance = run.ledgerUnits.merge(account, delta, Long::sum);
      run.unitsInIssue[fund] += delta;

      String ts = timestamp(day, tsCounter++);
      run.maxTimestamp = run.maxTimestamp.max(new BigInteger(ts));

      base.add(
          new Msg(
              transfersTopic,
              transferId.toString(),
              transferJson(
                  fundLedger, ts, transferId, delta, run.tenant, unitsInIssueAccount, account)));
      int entryOrdinal = base.size();
      base.add(
          new Msg(
              entriesTopic,
              account.toString(),
              entryJson(fundLedger, ts, transferId, VESTED, account, run.tenant, balance, 0L)));

      sameDayEntryOrdinals
          .computeIfAbsent(
              ((long) participant * FUNDS + fund) * BUSINESS_DAYS + day, k -> new ArrayList<>())
          .add(entryOrdinal);
    }

    // One UNITS_IN_ISSUE leg and one cash leg per fund: non-holding legs the projection must ignore
    // without dead-lettering (invariant 4) and without touching participant_units (invariant 3).
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
                  999_000L,
                  0L)));
      base.add(
          new Msg(
              entriesTopic,
              freshId(idRng).toString(),
              cashEntryJson(ts, freshId(idRng), freshId(idRng), run.tenant)));
    }

    // Build the wire stream: the base in order, plus redeliveries. "Assume every message arrives
    // more than once" (AGENTS.md §4). A redelivery is released at a later ordinal than its
    // original,
    // so it lands out of ledger-timestamp order.
    //
    // Release keys: base message k sits at slot `k << SLOT_BITS` (zero low bits). A redelivery of
    // it
    // is released after some later base slot k', at `(k' << SLOT_BITS) + jitter` with jitter in
    // [1, 1<<SLOT_BITS) — strictly between two base slots, so a redelivery can NEVER overwrite a
    // base message (an earlier bug: arithmetic that let a dup key land exactly on a base slot,
    // dropping that message and leaving the run one short).
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
    // Guarantee the guard is exercised: for every same-day group of >= 2 contributions, redeliver
    // the group's first entry after the whole group has been produced.
    for (List<Integer> ordinals : sameDayEntryOrdinals.values()) {
      if (ordinals.size() < 2) {
        continue;
      }
      int firstEntry = ordinals.get(0);
      int lastEntry = ordinals.get(ordinals.size() - 1);
      byRelease.put(((long) lastEntry << slotBits) + slotSize / 2, base.get(firstEntry));
    }
    run.stream.addAll(byRelease.values());
    return run;
  }

  private static long[] navSeries(Random rng) {
    // A volatile random walk in per-mille steps, plus one deep shock day that is forced strictly
    // below the opening price — so the series always falls, regardless of where the walk went.
    int[] steps = {-60, -35, -15, 10, 25, 45};
    long[] nav = new long[BUSINESS_DAYS];
    nav[0] = NAV_ONE;
    int shockDay = 1 + rng.nextInt(BUSINESS_DAYS - 1);
    for (int d = 1; d < BUSINESS_DAYS; d++) {
      if (d == shockDay) {
        nav[d] = Math.min(nav[d - 1], NAV_ONE) * 65 / 100; // below the previous day AND the open
      } else {
        long next = nav[d - 1] + nav[d - 1] * steps[rng.nextInt(steps.length)] / 1000;
        nav[d] = Math.max(next, 1_000L); // never zero or negative — NAV is a positive price
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
  // assertions against the projection
  // ---------------------------------------------------------------------------

  private static long transferRowCount(JdbcClient jdbc, String tenant) {
    return jdbc.sql("SELECT count(*) FROM projection.transfer WHERE tenant_id = :t")
        .param("t", tenant)
        .query(Long.class)
        .single();
  }

  private static long distinctTransferIds(JdbcClient jdbc, String tenant) {
    return jdbc.sql(
            "SELECT count(DISTINCT transfer_id) FROM projection.transfer WHERE tenant_id = :t")
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

  /**
   * Σ over every account of its latest holding — equals total units in issue once fully drained.
   */
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

  private static long latestUnits(JdbcClient jdbc, String tenant, BigInteger account) {
    return jdbc.sql(
            "SELECT units_scaled FROM projection.participant_units"
                + " WHERE tenant_id = :t AND participant_id = CAST(:p AS numeric)"
                + " ORDER BY effective_date DESC LIMIT 1")
        .param("t", tenant)
        .param("p", account.toString())
        .query(Long.class)
        .optional()
        .orElse(-1L);
  }

  private static long sumLatestUnits(JdbcClient jdbc, String tenant, int fundLedger) {
    return jdbc.sql(
            "SELECT COALESCE(SUM(u.units_scaled), 0) FROM ("
                + "  SELECT DISTINCT ON (participant_id) participant_id, units_scaled"
                + "  FROM projection.participant_units"
                + "  WHERE tenant_id = :t AND fund_id = :f"
                + "  ORDER BY participant_id, effective_date DESC) u")
        .param("t", tenant)
        .param("f", fundLedger)
        .query(Long.class)
        .single();
  }

  private static void assertHashChain(JdbcClient jdbc, String tenant, BigInteger account, Run run) {
    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT effective_date, units_scaled, last_transfer_id, prev_row_hash, row_hash"
                    + " FROM projection.participant_units"
                    + " WHERE tenant_id = :t AND participant_id = CAST(:p AS numeric)"
                    + " ORDER BY effective_date")
            .param("t", tenant)
            .param("p", account.toString())
            .query()
            .listOfRows();
    assertThat(rows).as("holding history for account %s", account).isNotEmpty();

    int fundLedger = run.accountFund.get(account);
    byte[] expectedPrev = null;
    for (Map<String, Object> row : rows) {
      LocalDate effectiveDate = ((java.sql.Date) row.get("effective_date")).toLocalDate();
      long units = ((Number) row.get("units_scaled")).longValue();
      BigInteger lastTransferId = new BigInteger(row.get("last_transfer_id").toString());

      assertThat((byte[]) row.get("prev_row_hash")).isEqualTo(expectedPrev);
      byte[] recomputed =
          chainHash(expectedPrev, account, fundLedger, effectiveDate, units, lastTransferId);
      assertThat((byte[]) row.get("row_hash"))
          .as("row_hash for account %s at %s", account, effectiveDate)
          .isEqualTo(recomputed);
      expectedPrev = recomputed;
    }
  }

  /** Mirrors {@link ParticipantUnitsProjection}'s pinned pre-image. */
  private static byte[] chainHash(
      byte[] prevRowHash,
      BigInteger participantId,
      int fundId,
      LocalDate effectiveDate,
      long unitsScaled,
      BigInteger lastTransferId) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      if (prevRowHash != null) {
        sha256.update(prevRowHash);
      }
      for (String field :
          List.of(
              participantId.toString(),
              Integer.toString(fundId),
              effectiveDate.toString(),
              Long.toString(unitsScaled),
              lastTransferId.toString())) {
        sha256.update((byte) 0x1F);
        sha256.update(field.getBytes(StandardCharsets.US_ASCII));
      }
      return sha256.digest();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // RabbitMQ plumbing
  // ---------------------------------------------------------------------------

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

  private static long deadLetterRecordCount() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(rabbit.getHost());
    factory.setPort(rabbit.getPort());
    factory.setUsername(rabbit.getUsername());
    factory.setPassword(rabbit.getPassword());
    try (Connection connection = factory.newConnection();
        Channel channel = connection.createChannel()) {
      return channel.messageCount(deadLetterTopic);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // deterministic id / timestamp / JSON helpers
  // ---------------------------------------------------------------------------

  private static BigInteger freshId(Random rng) {
    return new BigInteger(96, rng).add(BigInteger.ONE);
  }

  /**
   * The {@code day}-th weekday from {@link #weekStart} (weekends skipped) — the business date the
   * worker's {@code BusinessCalendar} derives from that day's noon-UTC timestamps.
   */
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

  /**
   * A strictly-increasing u64 nanosecond timestamp on {@link #businessDate(int)}; {@code seq} keeps
   * two movements on one day ordered.
   */
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
      long creditsPosted,
      long debitsPosted) {
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
        + ",\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":\""
        + debitsPosted
        + "\",\"creditsPending\":\"0\",\"creditsPosted\":\""
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

  // ---------------------------------------------------------------------------
  // fault injection — the "kill"
  // ---------------------------------------------------------------------------

  /**
   * Wraps the real {@link JdbcProjectionSink}. Between {@link #arm} and {@link #disarm} it throws
   * once at each of {@code killCount} randomised delivery ordinals — half before the delegate
   * commits (the record is replayed), half after it commits but before the AMQP message is
   * acknowledged (a {@code kill -9} window: the row is durable, so the redelivery must be an
   * idempotent no-op).
   */
  static final class FaultInjectingSink implements ProjectionSink {

    private final JdbcProjectionSink delegate;
    private final AtomicLong delivery = new AtomicLong();
    private volatile TreeMap<Long, Boolean> kills = new TreeMap<>();
    private final Set<Long> fired = java.util.concurrent.ConcurrentHashMap.newKeySet();

    FaultInjectingSink(JdbcProjectionSink delegate) {
      this.delegate = delegate;
    }

    void arm(long seed, long deliveries, int killCount) {
      Random rng = new Random(seed * 1_000_003L + 17);
      long lo = Math.max(1, (long) (deliveries * 0.02));
      long hi = Math.max(lo + killCount, (long) (deliveries * 0.95));
      TreeMap<Long, Boolean> planned = new TreeMap<>();
      while (planned.size() < killCount) {
        long ordinal = lo + (long) (rng.nextDouble() * (hi - lo));
        planned.putIfAbsent(ordinal, rng.nextBoolean());
      }
      delivery.set(0);
      fired.clear();
      kills = planned;
    }

    void disarm() {
      kills = new TreeMap<>();
      fired.clear();
    }

    int firedCount() {
      return fired.size();
    }

    @Override
    public void accept(LedgerTransferEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
      long ordinal = delivery.incrementAndGet();
      killBefore(ordinal);
      delegate.accept(event, consumer);
      killAfter(ordinal);
    }

    @Override
    public void accept(LedgerEntryEvent event, ProjectionCheckpoint.ConsumerRef consumer) {
      long ordinal = delivery.incrementAndGet();
      killBefore(ordinal);
      delegate.accept(event, consumer);
      killAfter(ordinal);
    }

    private void killBefore(long ordinal) {
      Boolean afterCommit = kills.get(ordinal);
      if (Boolean.FALSE.equals(afterCommit) && fired.add(ordinal)) {
        throw new InjectedCrash(ordinal, false);
      }
    }

    private void killAfter(long ordinal) {
      Boolean afterCommit = kills.get(ordinal);
      if (Boolean.TRUE.equals(afterCommit) && fired.add(ordinal)) {
        throw new InjectedCrash(ordinal, true);
      }
    }
  }

  /** A transient failure as far as the error handler is concerned: retry. */
  static final class InjectedCrash extends RuntimeException {
    InjectedCrash(long ordinal, boolean afterCommit) {
      super(
          "injected crash at delivery "
              + ordinal
              + (afterCommit ? " (row committed, message not acknowledged)" : " (before commit)"));
    }
  }

  @TestConfiguration
  static class FaultConfig {
    @Bean
    @Primary
    FaultInjectingSink faultInjectingSink(JdbcProjectionSink delegate) {
      return new FaultInjectingSink(delegate);
    }
  }
}
