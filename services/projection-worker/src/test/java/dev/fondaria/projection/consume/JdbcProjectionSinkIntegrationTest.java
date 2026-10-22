package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerEntryEvent;
import dev.fondaria.ledger.LedgerEventDecoder;
import dev.fondaria.ledger.LedgerTransferEvent;
import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.testing.PostgresFixture;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * {@link JdbcProjectionSink} against a real PostgreSQL (Task 5.4). No Kafka: the sink is driven
 * directly with events decoded the same way {@link LedgerEventListener} decodes them.
 *
 * <p>Tagged {@code integration}: boots Postgres, stays out of the plain {@code make verify} loop.
 * Fixtures are reused across runs (docs/ENGINEERING-RULES.md §4), so every assertion is scoped to a
 * {@code tenant_id} / {@code transfer_id} this test generated — never a count over a whole table.
 * The checkpoint is one row per consumer (ADR-0032), and every test method drives the sink as its
 * own freshly-named consumer, so its checkpoint row is the test's own and is asserted exactly.
 */
@Tag("integration")
@SpringJUnitConfig(JdbcProjectionSinkIntegrationTest.TestConfig.class)
class JdbcProjectionSinkIntegrationTest {

  private static final LedgerEventDecoder DECODER = new LedgerEventDecoder();

  // The interface, not JdbcProjectionSink: @Transactional makes it a proxy, and that is how
  // LedgerEventListener consumes it in production too.
  @Autowired private ProjectionSink sink;
  @Autowired private JdbcClient jdbc;
  @Autowired private BusinessCalendar calendar;

  // A fresh instance per test method, so each test's checkpoint row is its own (ADR-0032 §1).
  private final ProjectionCheckpoint.ConsumerRef consumer =
      ProjectionCheckpoint.ConsumerRef.of(
          "sink-test-" + UUID.randomUUID(), "amq.ctag-test", "projection.test");

  @BeforeAll
  static void bootPostgres() {
    // Start the container before the Spring context builds the DataSource from it: the fixture
    // sets DOCKER_HOST as a side effect and Testcontainers must see it before its first client.
    PostgresFixture.getInstance();
  }

  // A Wednesday, 12:00 UTC (06:00 Mexico City) — comfortably before the 18:00 cutoff, so the
  // business date is simply that calendar day in the platform zone.
  private static final Instant WEEKDAY_NOON = Instant.parse("2026-08-19T12:00:00Z");
  private static final String WEEKDAY_NOON_NANOS = WEEKDAY_NOON.getEpochSecond() + "000000000";

  // Three business days a week apart at noon UTC for the sparse-holding tests (Task 5.5) — spaced
  // so a query "as-of" a date strictly between two of them exercises the sparse read.
  private static final Instant DAY_1 = Instant.parse("2026-08-17T12:00:00Z"); // Monday
  private static final Instant DAY_2 = Instant.parse("2026-08-24T12:00:00Z"); // Monday
  private static final Instant DAY_3 = Instant.parse("2026-08-31T12:00:00Z"); // Monday
  private static final Instant DAY_4 = Instant.parse("2026-09-07T12:00:00Z"); // Monday

  private static final int VESTED = LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED.code();
  private static final int UNITS_IN_ISSUE = LedgerCodes.AccountCode.UNITS_IN_ISSUE.code();

  @Test
  @DisplayName("delivering the same event 100 times produces exactly one row")
  void sameEventAHundredTimesIsOneRow() {
    String tenant = freshTenant();
    BigInteger transferId = freshId();
    LedgerTransferEvent event =
        cashTransfer(transferId, tenant, "125000", WEEKDAY_NOON_NANOS, "1", "0");

    for (int i = 0; i < 100; i++) {
      sink.accept(event, consumer);
    }

    assertThat(rowCount(tenant, transferId)).isEqualTo(1);
    Map<String, Object> row = row(tenant, transferId);
    assertThat(new BigInteger(row.get("transfer_id").toString())).isEqualTo(transferId);
    assertThat(row.get("tenant_id")).isEqualTo(tenant);
    assertThat(((Number) row.get("ledger_id")).intValue()).isEqualTo(484);
    assertThat(((Number) row.get("amount_scaled")).longValue()).isEqualTo(125_000L);
    assertThat(((Number) row.get("code")).intValue()).isEqualTo(1);
    assertThat(((java.sql.Date) row.get("booking_date")).toLocalDate())
        .isEqualTo(calendar.businessDateOf(WEEKDAY_NOON));
    assertThat(((java.sql.Date) row.get("value_date")).toLocalDate())
        .isEqualTo(calendar.businessDateOf(WEEKDAY_NOON));
    assertThat(row.get("nav_version_id")).isNull();
    assertThat(row.get("reference")).isNull();
    assertThat(checkpoint()).isEqualTo(new BigInteger(WEEKDAY_NOON_NANOS));
  }

  @Test
  @DisplayName("the checkpoint advances monotonically and an older event never regresses it")
  void checkpointIsMonotonic() {
    String tenant = freshTenant();
    String tHigh = "1755604800000000900";
    String tLow = "1755604800000000100";

    sink.accept(cashTransfer(freshId(), tenant, "1000", tHigh, "1", "0"), consumer);
    BigInteger afterHigh = checkpoint();
    assertThat(afterHigh).isEqualTo(new BigInteger(tHigh));

    // An out-of-order redelivery with an earlier ledger timestamp must not move the cursor back.
    sink.accept(cashTransfer(freshId(), tenant, "1000", tLow, "1", "0"), consumer);
    assertThat(checkpoint()).isEqualTo(afterHigh);
  }

  @Test
  @DisplayName("a unit-ledger transfer projects with its scaled unit amount and fund ledger id")
  void unitLedgerTransfer() {
    String tenant = freshTenant();
    BigInteger transferId = freshId();

    sink.accept(
        unitTransfer(transferId, tenant, 5001, "75000000000", WEEKDAY_NOON_NANOS), consumer);

    Map<String, Object> row = row(tenant, transferId);
    assertThat(((Number) row.get("ledger_id")).intValue()).isEqualTo(5001);
    assertThat(((Number) row.get("amount_scaled")).longValue()).isEqualTo(75_000_000_000L);
  }

  @Test
  @DisplayName(
      "the external reference is stored when the transfer carried one, null when it did not")
  void externalReferenceHandling() {
    String tenant = freshTenant();

    BigInteger withRef = freshId();
    sink.accept(
        cashTransfer(
            withRef,
            tenant,
            "1000",
            WEEKDAY_NOON_NANOS,
            "1",
            "340282366920938463463374607431768211455"),
        consumer);
    assertThat(row(tenant, withRef).get("reference"))
        .isEqualTo("340282366920938463463374607431768211455");

    BigInteger withoutRef = freshId();
    sink.accept(cashTransfer(withoutRef, tenant, "1000", WEEKDAY_NOON_NANOS, "1", "0"), consumer);
    assertThat(row(tenant, withoutRef).get("reference")).isNull();
  }

  @Test
  @DisplayName(
      "the projection commits within accept() — nothing the caller does afterward can undo it")
  void commitsWithinAccept() {
    String tenant = freshTenant();
    BigInteger transferId = freshId();

    sink.accept(cashTransfer(transferId, tenant, "125000", WEEKDAY_NOON_NANOS, "1", "0"), consumer);

    // A brand-new connection (autocommit) — if accept() had not committed before returning,
    // this read would not see the row. The listener's acknowledge() (or any cache write, were
    // there one) happens strictly after this point and so cannot roll it back.
    JdbcClient independent = JdbcClient.create(freshDataSource());
    Long seen =
        independent
            .sql(
                "SELECT count(*) FROM projection.transfer WHERE tenant_id = :t"
                    + " AND transfer_id = :id")
            .param("t", tenant)
            .param("id", transferId)
            .query(Long.class)
            .single();
    assertThat(seen).isEqualTo(1L);
  }

  // ---------------------------------------------------------------------------
  // Task 5.5 — sparse unit position projection
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "a participant contributing on two dates has exactly two rows; as-of reads the older")
  void twoContributionsTwoRows() {
    String tenant = freshTenant();
    BigInteger account = freshId();

    // 100 units held after day 1, 150 after day 2 (the ledger reports cumulative posted counters).
    sink.accept(unitEntry(account, tenant, VESTED, freshId(), DAY_1, "100", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, freshId(), DAY_2, "150", "0"), consumer);

    assertThat(holdingRowCount(tenant, account)).isEqualTo(2);
    assertThat(unitsAsOf(tenant, account, calendar.businessDateOf(DAY_1))).isEqualTo(100L);
    // "Units as-of the 10th returns the 1st's row": a date strictly between the two changes.
    assertThat(unitsAsOf(tenant, account, calendar.businessDateOf(DAY_1).plusDays(3)))
        .isEqualTo(100L);
    assertThat(unitsAsOf(tenant, account, calendar.businessDateOf(DAY_2))).isEqualTo(150L);
  }

  @Test
  @DisplayName("delivering the same unit entry 100 times produces one row with the right holding")
  void sameUnitEntryAHundredTimes() {
    String tenant = freshTenant();
    BigInteger account = freshId();
    BigInteger transferId = freshId();
    LedgerEntryEvent entry =
        unitEntry(account, tenant, VESTED, transferId, DAY_1, "4200000000", "0");

    for (int i = 0; i < 100; i++) {
      sink.accept(entry, consumer);
    }

    assertThat(holdingRowCount(tenant, account)).isEqualTo(1);
    Map<String, Object> row = holdingRow(tenant, account, calendar.businessDateOf(DAY_1));
    assertThat(((Number) row.get("units_scaled")).longValue()).isEqualTo(4_200_000_000L);
    assertThat(new BigInteger(row.get("last_transfer_id").toString())).isEqualTo(transferId);
    assertThat(new BigInteger(row.get("last_ledger_timestamp").toString()))
        .isEqualTo(new BigInteger(DAY_1.getEpochSecond() + "000000000"));
    assertThat((byte[]) row.get("row_hash")).hasSize(32);
  }

  @Test
  @DisplayName("redelivering an older entry for the same date does not overwrite a newer holding")
  void olderEntryDoesNotOverwrite() {
    String tenant = freshTenant();
    BigInteger account = freshId();
    String tsHigh = DAY_1.getEpochSecond() + "000000900";
    String tsLow = DAY_1.getEpochSecond() + "000000100";

    sink.accept(unitEntryAt(account, tenant, VESTED, freshId(), tsHigh, "250", "0"), consumer);
    sink.accept(unitEntryAt(account, tenant, VESTED, freshId(), tsLow, "10", "0"), consumer);

    Map<String, Object> row = holdingRow(tenant, account, calendar.businessDateOf(DAY_1));
    assertThat(((Number) row.get("units_scaled")).longValue()).isEqualTo(250L);
    assertThat(new BigInteger(row.get("last_ledger_timestamp").toString()))
        .isEqualTo(new BigInteger(tsHigh));
  }

  @Test
  @DisplayName("units are creditsPosted − debitsPosted and never negative")
  void holdingIsPostedCreditsMinusDebits() {
    String tenant = freshTenant();
    BigInteger account = freshId();

    // A vesting move debits the unvested account: 900 credited over time, 300 moved out.
    sink.accept(unitEntry(account, tenant, VESTED, freshId(), DAY_1, "900", "300"), consumer);

    Map<String, Object> row = holdingRow(tenant, account, calendar.businessDateOf(DAY_1));
    assertThat(((Number) row.get("units_scaled")).longValue()).isEqualTo(600L);
  }

  @Test
  @DisplayName("UNITS_IN_ISSUE and cash entries write no holding row")
  void nonHoldingEntriesAreIgnored() {
    String tenant = freshTenant();
    BigInteger unitsInIssueAccount = freshId();
    BigInteger cashAccount = freshId();

    sink.accept(
        unitEntry(unitsInIssueAccount, tenant, UNITS_IN_ISSUE, freshId(), DAY_1, "999", "0"),
        consumer);
    sink.accept(
        DECODER.decodeEntry(
            cashEntryJson(
                cashAccount.toString(), tenant, freshId().toString(), WEEKDAY_NOON_NANOS)),
        consumer);

    assertThat(holdingRowCount(tenant, unitsInIssueAccount)).isZero();
    assertThat(holdingRowCount(tenant, cashAccount)).isZero();
  }

  @Test
  @DisplayName("the per-(participant,fund) hash chain is continuous and recomputable from the rows")
  void hashChainIsContinuousAndRecomputable() {
    String tenant = freshTenant();
    BigInteger account = freshId();
    BigInteger t1 = freshId();
    BigInteger t2 = freshId();
    BigInteger t3 = freshId();

    sink.accept(unitEntry(account, tenant, VESTED, t1, DAY_1, "100", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, t2, DAY_2, "300", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, t3, DAY_3, "250", "0"), consumer);

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT effective_date, units_scaled, last_transfer_id, prev_row_hash, row_hash"
                    + " FROM projection.participant_units"
                    + " WHERE tenant_id = :t AND participant_id = :p ORDER BY effective_date")
            .param("t", tenant)
            .param("p", account)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(3);

    int fundId = fundLedgerId();
    byte[] expectedPrev = null;
    long[] units = {100L, 300L, 250L};
    BigInteger[] transfers = {t1, t2, t3};
    LocalDate[] dates = {
      calendar.businessDateOf(DAY_1), calendar.businessDateOf(DAY_2), calendar.businessDateOf(DAY_3)
    };

    for (int i = 0; i < 3; i++) {
      Map<String, Object> row = rows.get(i);
      assertThat((byte[]) row.get("prev_row_hash")).isEqualTo(expectedPrev);
      byte[] recomputed =
          chainHash(expectedPrev, account, fundId, dates[i], units[i], transfers[i]);
      assertThat((byte[]) row.get("row_hash")).isEqualTo(recomputed);
      expectedPrev = recomputed;
    }
  }

  @Test
  @DisplayName(
      "cross-date reordering repairs the hash chain to the same result as in-order delivery")
  void hashChainRepairsUnderCrossDateReordering() {
    // Task F.3 (ADR-0024): the hash chain used to assume delivery arrives in effective_date
    // order. Deliver the same four dates as an in-order run would, but scrambled — day 3 first
    // (nothing to chain to yet), then day 1 (an EARLIER date arriving after a later one already
    // has a row — the exact failure this task fixes), then day 4, then day 2 (landing between two
    // dates that already both exist). The expected chain below is byte-for-byte the same chain
    // hashChainIsContinuousAndRecomputable asserts for in-order delivery.
    String tenant = freshTenant();
    BigInteger account = freshId();
    BigInteger t1 = freshId();
    BigInteger t2 = freshId();
    BigInteger t3 = freshId();
    BigInteger t4 = freshId();

    sink.accept(unitEntry(account, tenant, VESTED, t3, DAY_3, "250", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, t1, DAY_1, "100", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, t4, DAY_4, "400", "0"), consumer);
    sink.accept(unitEntry(account, tenant, VESTED, t2, DAY_2, "300", "0"), consumer);

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT effective_date, units_scaled, last_transfer_id, prev_row_hash, row_hash"
                    + " FROM projection.participant_units"
                    + " WHERE tenant_id = :t AND participant_id = :p ORDER BY effective_date")
            .param("t", tenant)
            .param("p", account)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(4);

    int fundId = fundLedgerId();
    byte[] expectedPrev = null;
    long[] units = {100L, 300L, 250L, 400L};
    BigInteger[] transfers = {t1, t2, t3, t4};
    LocalDate[] dates = {
      calendar.businessDateOf(DAY_1),
      calendar.businessDateOf(DAY_2),
      calendar.businessDateOf(DAY_3),
      calendar.businessDateOf(DAY_4)
    };

    for (int i = 0; i < 4; i++) {
      Map<String, Object> row = rows.get(i);
      assertThat((byte[]) row.get("prev_row_hash"))
          .as("prev_row_hash at %s after scrambled delivery", dates[i])
          .isEqualTo(expectedPrev);
      byte[] recomputed =
          chainHash(expectedPrev, account, fundId, dates[i], units[i], transfers[i]);
      assertThat((byte[]) row.get("row_hash"))
          .as("row_hash at %s after scrambled delivery", dates[i])
          .isEqualTo(recomputed);
      expectedPrev = recomputed;
    }
  }

  @Test
  @DisplayName(
      "concurrent entries for the same holding on different dates still produce a well-formed"
          + " chain")
  void concurrentEntriesProduceAWellFormedChain() throws Exception {
    // Task F.3: the single-consumer-per-account guarantee is no longer assumed, so
    // ParticipantUnitsProjection's own advisory lock must be what prevents a read-then-write race
    // between the chain-repair queries and the UPSERT/RELINK writes. Fire three dates for one
    // holding from three threads at once; whatever interleaving actually happens, the stored chain
    // must still be internally consistent — every row's prev_row_hash equal to its immediate
    // predecessor's row_hash.
    String tenant = freshTenant();
    BigInteger account = freshId();
    BigInteger t1 = freshId();
    BigInteger t2 = freshId();
    BigInteger t3 = freshId();

    // Three threads share this test's one ConsumerRef, which production never does (one consumer,
    // one thread, one checkpoint row — ADR-0032). Harmless here: this test is about the advisory
    // lock, and the shared row only serializes the checkpoint write. Do not copy the pattern.
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<?>> futures =
          List.of(
              pool.submit(
                  () ->
                      sink.accept(
                          unitEntry(account, tenant, VESTED, t1, DAY_1, "100", "0"), consumer)),
              pool.submit(
                  () ->
                      sink.accept(
                          unitEntry(account, tenant, VESTED, t2, DAY_2, "300", "0"), consumer)),
              pool.submit(
                  () ->
                      sink.accept(
                          unitEntry(account, tenant, VESTED, t3, DAY_3, "250", "0"), consumer)));
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError("a concurrent sink.accept() failed or hung", e);
    } finally {
      pool.shutdown();
    }

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT effective_date, units_scaled, prev_row_hash, row_hash"
                    + " FROM projection.participant_units"
                    + " WHERE tenant_id = :t AND participant_id = :p ORDER BY effective_date")
            .param("t", tenant)
            .param("p", account)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(3);
    byte[] expectedPrev = null;
    for (Map<String, Object> row : rows) {
      assertThat((byte[]) row.get("prev_row_hash")).isEqualTo(expectedPrev);
      expectedPrev = (byte[]) row.get("row_hash");
    }
    // Each date's single entry still posted its own units — the lock serializes, it never drops.
    assertThat(((Number) rows.get(0).get("units_scaled")).longValue()).isEqualTo(100L);
    assertThat(((Number) rows.get(1).get("units_scaled")).longValue()).isEqualTo(300L);
    assertThat(((Number) rows.get(2).get("units_scaled")).longValue()).isEqualTo(250L);
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // ADR-0034 — a batch of deliveries in one transaction
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("a batch is one transaction: a leg that fails leaves nothing, checkpoint included")
  void aBatchIsOneTransaction() {
    String tenant = freshTenant();
    BigInteger transferId = freshId();
    BigInteger account = freshId();
    List<ProjectionSink.Delivery> batch =
        List.of(
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(transferId, tenant, "125000", WEEKDAY_NOON_NANOS, "1", "0"), consumer),
            // debits above credits: ParticipantUnitsProjection refuses a negative holding
            new ProjectionSink.Delivery.Entry(
                unitEntry(account, tenant, VESTED, freshId(), DAY_1, "100", "300"), consumer));

    assertThatThrownBy(() -> sink.acceptBatch(batch)).isInstanceOf(IllegalStateException.class);

    assertThat(rowCount(tenant, transferId)).isZero();
    assertThat(holdingRowCount(tenant, account)).isZero();
    assertThat(checkpoint()).isEqualTo(BigInteger.valueOf(-1));
  }

  @Test
  @DisplayName(
      "a scrambled batch converges to the same holding rows and hash chain as per-event delivery,"
          + " with units rising and falling")
  void aBatchConvergesLikeOneTransactionPerEvent() {
    String tenant = freshTenant();
    BigInteger batched = freshId();
    BigInteger perEvent = freshId();
    BigInteger[] transfers = {freshId(), freshId(), freshId(), freshId(), freshId(), freshId()};
    // Cumulative posted counters a holding reports over four weeks: two credits on day 1, a
    // vesting move out on day 2 (falls), a credit then a large move out on day 3 (rises, then
    // falls below day 1), a small credit on day 4.
    String[][] legs = {
      {DAY_1.getEpochSecond() + "000000100", "1000", "0"},
      {DAY_1.getEpochSecond() + "000000200", "1500", "0"},
      {DAY_2.getEpochSecond() + "000000000", "1500", "700"},
      {DAY_3.getEpochSecond() + "000000000", "2600", "700"},
      {DAY_3.getEpochSecond() + "000000050", "2600", "2100"},
      {DAY_4.getEpochSecond() + "000000000", "2650", "2100"},
    };

    for (int i = 0; i < legs.length; i++) {
      sink.accept(
          unitEntryAt(perEvent, tenant, VESTED, transfers[i], legs[i][0], legs[i][1], legs[i][2]),
          consumer);
    }
    List<ProjectionSink.Delivery> batch = new java.util.ArrayList<>();
    for (int i : new int[] {4, 1, 5, 0, 3, 2}) {
      batch.add(
          new ProjectionSink.Delivery.Entry(
              unitEntryAt(
                  batched, tenant, VESTED, transfers[i], legs[i][0], legs[i][1], legs[i][2]),
              consumer));
    }
    sink.acceptBatch(batch);

    List<Map<String, Object>> batchedRows = holdingRows(tenant, batched);
    assertThat(batchedRows).hasSize(4);
    assertThat(batchedRows)
        .extracting(r -> ((Number) r.get("units_scaled")).longValue())
        .containsExactly(1500L, 800L, 500L, 550L);
    assertThat(batchedRows)
        .extracting(
            r -> List.of(r.get("effective_date"), r.get("units_scaled"), r.get("last_transfer_id")))
        .containsExactlyElementsOf(
            holdingRows(tenant, perEvent).stream()
                .map(
                    r ->
                        List.of(
                            r.get("effective_date"),
                            r.get("units_scaled"),
                            r.get("last_transfer_id")))
                .toList());

    byte[] expectedPrev = null;
    for (Map<String, Object> row : batchedRows) {
      assertThat((byte[]) row.get("prev_row_hash")).isEqualTo(expectedPrev);
      byte[] recomputed =
          chainHash(
              expectedPrev,
              batched,
              fundLedgerId(),
              ((java.sql.Date) row.get("effective_date")).toLocalDate(),
              ((Number) row.get("units_scaled")).longValue(),
              new BigInteger(row.get("last_transfer_id").toString()));
      assertThat((byte[]) row.get("row_hash")).isEqualTo(recomputed);
      expectedPrev = recomputed;
    }
  }

  @Test
  @DisplayName(
      "a batch landing on both sides of a committed row repairs the chain inside its own"
          + " transaction, like per-event delivery")
  void aBatchRepairsTheChainAroundACommittedRow() {
    String tenant = freshTenant();
    BigInteger batched = freshId();
    BigInteger perEvent = freshId();
    BigInteger t1 = freshId();
    BigInteger t2 = freshId();
    BigInteger t3 = freshId();

    // Day 2 is already committed for both holdings; day 1 then has to relink day 2 onto itself,
    // and day 3 has to chain onto that relinked day 2 -- for the batch, a row its own still
    // uncommitted transaction just rewrote. The holding falls on day 3.
    for (BigInteger account : List.of(batched, perEvent)) {
      sink.accept(unitEntry(account, tenant, VESTED, t2, DAY_2, "900", "0"), consumer);
    }
    sink.accept(unitEntry(perEvent, tenant, VESTED, t1, DAY_1, "400", "0"), consumer);
    sink.accept(unitEntry(perEvent, tenant, VESTED, t3, DAY_3, "900", "650"), consumer);
    sink.acceptBatch(
        List.of(
            new ProjectionSink.Delivery.Entry(
                unitEntry(batched, tenant, VESTED, t3, DAY_3, "900", "650"), consumer),
            new ProjectionSink.Delivery.Entry(
                unitEntry(batched, tenant, VESTED, t1, DAY_1, "400", "0"), consumer)));

    List<Map<String, Object>> rows = holdingRows(tenant, batched);
    assertThat(rows)
        .extracting(r -> ((Number) r.get("units_scaled")).longValue())
        .containsExactly(400L, 900L, 250L);
    byte[] expectedPrev = null;
    for (Map<String, Object> row : rows) {
      assertThat((byte[]) row.get("prev_row_hash")).isEqualTo(expectedPrev);
      byte[] recomputed =
          chainHash(
              expectedPrev,
              batched,
              fundLedgerId(),
              ((java.sql.Date) row.get("effective_date")).toLocalDate(),
              ((Number) row.get("units_scaled")).longValue(),
              new BigInteger(row.get("last_transfer_id").toString()));
      assertThat((byte[]) row.get("row_hash")).isEqualTo(recomputed);
      expectedPrev = recomputed;
    }
    assertThat(
            holdingRows(tenant, batched).stream()
                .map(r -> List.of(r.get("effective_date"), r.get("units_scaled")))
                .toList())
        .isEqualTo(
            holdingRows(tenant, perEvent).stream()
                .map(r -> List.of(r.get("effective_date"), r.get("units_scaled")))
                .toList());
  }

  @Test
  @DisplayName("a batch advances each consumer's checkpoint once, to its own highest timestamp")
  void aBatchAdvancesEachConsumersCheckpointToItsHighest() {
    String tenant = freshTenant();
    ProjectionCheckpoint.ConsumerRef other =
        ProjectionCheckpoint.ConsumerRef.of(
            "sink-test-" + UUID.randomUUID(), "amq.ctag-other", "projection.test.other");
    String t100 = "1755604800000000100";
    String t200 = "1755604800000000200";
    String t300 = "1755604800000000300";

    sink.acceptBatch(
        List.of(
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(freshId(), tenant, "1000", t300, "1", "0"), consumer),
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(freshId(), tenant, "1000", t200, "1", "0"), other),
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(freshId(), tenant, "1000", t100, "1", "0"), consumer)));

    assertThat(checkpoint()).isEqualTo(new BigInteger(t300));
    assertThat(checkpointOf(other)).isEqualTo(new BigInteger(t200));
  }

  @Test
  @DisplayName("a batch redelivered after a crash between commit and ack changes nothing")
  void aRedeliveredBatchIsANoOp() {
    String tenant = freshTenant();
    BigInteger account = freshId();
    BigInteger t1 = freshId();
    BigInteger t2 = freshId();
    List<ProjectionSink.Delivery> batch =
        List.of(
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(t1, tenant, "500", WEEKDAY_NOON_NANOS, "1", "0"), consumer),
            new ProjectionSink.Delivery.Transfer(
                cashTransfer(t2, tenant, "700", WEEKDAY_NOON_NANOS, "1", "0"), consumer),
            new ProjectionSink.Delivery.Entry(
                unitEntry(account, tenant, VESTED, t1, DAY_1, "300", "0"), consumer));

    sink.acceptBatch(batch);
    List<String> before = comparable(holdingRows(tenant, account));
    sink.acceptBatch(batch);

    assertThat(rowCount(tenant, t1)).isEqualTo(1);
    assertThat(rowCount(tenant, t2)).isEqualTo(1);
    assertThat(comparable(holdingRows(tenant, account))).hasSize(1).isEqualTo(before);
  }

  /** Holding rows as strings: a row's hashes are byte arrays, which do not compare by value. */
  private static List<String> comparable(List<Map<String, Object>> rows) {
    java.util.HexFormat hex = java.util.HexFormat.of();
    return rows.stream()
        .map(
            r ->
                r.get("effective_date")
                    + "|"
                    + r.get("units_scaled")
                    + "|"
                    + r.get("last_transfer_id")
                    + "|"
                    + (r.get("prev_row_hash") == null
                        ? "-"
                        : hex.formatHex((byte[]) r.get("prev_row_hash")))
                    + "|"
                    + hex.formatHex((byte[]) r.get("row_hash")))
        .toList();
  }

  private List<Map<String, Object>> holdingRows(String tenant, BigInteger account) {
    return jdbc.sql(
            "SELECT effective_date, units_scaled, last_transfer_id, prev_row_hash, row_hash"
                + " FROM projection.participant_units"
                + " WHERE tenant_id = :t AND participant_id = :p ORDER BY effective_date")
        .param("t", tenant)
        .param("p", account)
        .query()
        .listOfRows();
  }

  private BigInteger checkpointOf(ProjectionCheckpoint.ConsumerRef ref) {
    return jdbc.sql(
            "SELECT last_ledger_timestamp FROM projection.projection_consumer_checkpoint"
                + " WHERE projection_name = :n AND consumer_id = :c")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .param("c", ref.id())
        .query(BigInteger.class)
        .single();
  }

  private long holdingRowCount(String tenant, BigInteger account) {
    return jdbc.sql(
            "SELECT count(*) FROM projection.participant_units"
                + " WHERE tenant_id = :t AND participant_id = :p")
        .param("t", tenant)
        .param("p", account)
        .query(Long.class)
        .single();
  }

  private Map<String, Object> holdingRow(
      String tenant, BigInteger account, LocalDate effectiveDate) {
    return jdbc.sql(
            "SELECT * FROM projection.participant_units"
                + " WHERE tenant_id = :t AND participant_id = :p AND effective_date = :d")
        .param("t", tenant)
        .param("p", account)
        .param("d", effectiveDate)
        .query()
        .singleRow();
  }

  private Long unitsAsOf(String tenant, BigInteger account, LocalDate asOf) {
    return jdbc.sql(
            "SELECT units_scaled FROM projection.participant_units"
                + " WHERE tenant_id = :t AND participant_id = :p AND effective_date <= :d"
                + " ORDER BY effective_date DESC LIMIT 1")
        .param("t", tenant)
        .param("p", account)
        .param("d", asOf)
        .query(Long.class)
        .single();
  }

  /** The unit ledger id every {@link #unitEntry} uses — an arbitrary fund ledger (>= 1001). */
  private static int fundLedgerId() {
    return 5001;
  }

  private static LedgerEntryEvent unitEntry(
      BigInteger account,
      String tenantRef,
      int accountCode,
      BigInteger transferId,
      Instant at,
      String creditsPosted,
      String debitsPosted) {
    return unitEntryAt(
        account,
        tenantRef,
        accountCode,
        transferId,
        at.getEpochSecond() + "000000000",
        creditsPosted,
        debitsPosted);
  }

  private static LedgerEntryEvent unitEntryAt(
      BigInteger account,
      String tenantRef,
      int accountCode,
      BigInteger transferId,
      String timestampNanos,
      String creditsPosted,
      String debitsPosted) {
    return DECODER.decodeEntry(
        "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":"
            + fundLedgerId()
            + ",\"timestamp\":\""
            + timestampNanos
            + "\",\"transferId\":\""
            + transferId
            + "\",\"transferCode\":2,\"transferTimestamp\":\""
            + timestampNanos
            + "\",\"amount\":\"1\",\"direction\":\"CREDIT\",\"accountId\":\""
            + account
            + "\",\"accountCode\":"
            + accountCode
            + ",\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":\""
            + debitsPosted
            + "\",\"creditsPending\":\"0\",\"creditsPosted\":\""
            + creditsPosted
            + "\",\"accountUserData128\":\"0\",\"accountUserData64\":\""
            + tenantRef
            + "\",\"accountUserData32\":0,\"pendingId\":\"0\"}");
  }

  private static String cashEntryJson(
      String account, String tenantRef, String transferId, String timestampNanos) {
    return "{\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":484,\"timestamp\":\""
        + timestampNanos
        + "\",\"transferId\":\""
        + transferId
        + "\",\"transferCode\":1,\"transferTimestamp\":\""
        + timestampNanos
        + "\",\"amount\":\"125000\",\"direction\":\"DEBIT\",\"accountId\":\""
        + account
        + "\",\"accountCode\":5,\"accountFlags\":0,\"debitsPending\":\"0\",\"debitsPosted\":"
        + "\"125000\",\"creditsPending\":\"0\",\"creditsPosted\":\"0\",\"accountUserData128\":\"0\","
        + "\"accountUserData64\":\""
        + tenantRef
        + "\",\"accountUserData32\":0,\"pendingId\":\"0\"}";
  }

  /** Mirrors {@code ParticipantUnitsProjection.chainHash} — the proof the chain is recomputable. */
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

  private long rowCount(String tenant, BigInteger transferId) {
    return jdbc.sql(
            "SELECT count(*) FROM projection.transfer WHERE tenant_id = :t AND transfer_id = :id")
        .param("t", tenant)
        .param("id", transferId)
        .query(Long.class)
        .single();
  }

  private Map<String, Object> row(String tenant, BigInteger transferId) {
    return jdbc.sql("SELECT * FROM projection.transfer WHERE tenant_id = :t AND transfer_id = :id")
        .param("t", tenant)
        .param("id", transferId)
        .query()
        .singleRow();
  }

  private BigInteger checkpoint() {
    return jdbc.sql(
            "SELECT last_ledger_timestamp FROM projection.projection_consumer_checkpoint"
                + " WHERE projection_name = :n AND consumer_id = :c")
        .param("n", ProjectionCheckpoint.PROJECTION_NAME)
        .param("c", consumer.id())
        .query(BigInteger.class)
        .optional()
        .orElse(BigInteger.valueOf(-1));
  }

  /** A tenant ref as it travels on the wire: the unsigned base-10 form of a 64-bit TenantRef. */
  private static String freshTenant() {
    return TenantRef.derive(UUID.randomUUID(), new OrganizationId(UUID.randomUUID()))
        .asUnsignedDecimal();
  }

  private static BigInteger freshId() {
    return new BigInteger(96, new java.util.Random()).add(BigInteger.ONE);
  }

  private static DataSource freshDataSource() {
    PostgresFixture pg = PostgresFixture.getInstance();
    return new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  private static LedgerTransferEvent cashTransfer(
      BigInteger transferId,
      String tenantRef,
      String amount,
      String timestampNanos,
      String code,
      String userData128) {
    return DECODER.decodeTransfer(
        transferJson(
            transferId.toString(),
            484,
            amount,
            timestampNanos,
            code,
            userData128,
            tenantRef,
            "700000000000000010",
            "700000000000000020"));
  }

  private static LedgerTransferEvent unitTransfer(
      BigInteger transferId,
      String tenantRef,
      long fundLedgerId,
      String scaledUnits,
      String timestampNanos) {
    return DECODER.decodeTransfer(
        transferJson(
            transferId.toString(),
            fundLedgerId,
            scaledUnits,
            timestampNanos,
            "2",
            "0",
            tenantRef,
            "700000000000000030",
            "700000000000000040"));
  }

  private static String transferJson(
      String transferId,
      long ledger,
      String amount,
      String timestampNanos,
      String code,
      String userData128,
      String userData64,
      String debitAccountId,
      String creditAccountId) {
    return "{"
        + "\"schemaVersion\":\"v1\",\"eventType\":\"single_phase\",\"ledger\":"
        + ledger
        + ","
        + "\"timestamp\":\""
        + timestampNanos
        + "\",\"transferId\":\""
        + transferId
        + "\","
        + "\"amount\":\""
        + amount
        + "\",\"pendingId\":\"0\",\"transferCode\":"
        + code
        + ","
        + "\"transferFlags\":0,\"transferTimestamp\":\""
        + timestampNanos
        + "\","
        + "\"userData128\":\""
        + userData128
        + "\",\"userData64\":\""
        + userData64
        + "\","
        + "\"userData32\":0,\"debitAccountId\":\""
        + debitAccountId
        + "\","
        + "\"creditAccountId\":\""
        + creditAccountId
        + "\",\"debitAccountCode\":1,"
        + "\"creditAccountCode\":2}";
  }

  @Configuration
  @EnableTransactionManagement
  @EnableConfigurationProperties(CheckpointProperties.class)
  @Import({
    ProjectionCheckpoint.class,
    ParticipantUnitsProjection.class,
    JdbcProjectionSink.class,
    CdcLagMetrics.class
  })
  static class TestConfig {

    @Bean
    DataSource dataSource() {
      return freshDataSource();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    BusinessCalendar businessCalendar() {
      // The production defaults from CalendarProperties: platform zone, 18:00 cutoff, no holidays.
      return new BusinessCalendar(ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.of());
    }

    @Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    java.time.Clock clock() {
      return dev.fondaria.common.StorableClock.systemUTC();
    }

    @Bean
    TraceCorrelationReader traceCorrelationReader() {
      // No Redis in this narrow slice: Task 11.2's trace continuation is exercised by its own
      // tests, not this one, which is about the write itself.
      return TraceCorrelationReader.NONE;
    }
  }
}
