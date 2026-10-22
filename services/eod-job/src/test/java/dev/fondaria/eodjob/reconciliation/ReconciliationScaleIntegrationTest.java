package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 7.2's fourth criterion: <b>the pass completes for a million participants within the window,
 * and its duration is recorded.</b>
 *
 * <h2>What is real here and what is synthetic, stated plainly</h2>
 *
 * <p>The <b>pass</b> is entirely real: the keyset scan over a million distinct holdings in {@code
 * workflow.allotment}, two million derived account ids read from a real TigerBeetle in batches of
 * 8,189, the {@code DISTINCT ON} scan over a million {@code projection.participant_units} rows, and
 * the run and its findings written to a real PostgreSQL. Those four are the whole cost of the
 * comparison, and every one of them is paid in full below.
 *
 * <p>The <b>book</b> is synthetic and empty: a million participants each holding zero. Giving the
 * ledger a million real holdings would mean posting two million transfers through allotment-job,
 * which is minutes of writes to measure a read — and Task 21.5 already establishes at a realistic
 * size that those postings tie. What that choice buys is worth more than the realism it gives up:
 * the pass must find <b>no break at all</b> across a million participants, so this is also the test
 * that a scan of that size does not invent drift out of a page boundary, a batch boundary or an
 * account the ledger has never heard of.
 *
 * <p>Correctness against a book that actually holds units is {@code
 * InternalReconciliationEndToEndTest}'s, over the real contribution path.
 *
 * <h2>The window</h2>
 *
 * <p>phase-07 states the constraint the daily close lives under: the demo runs a simulated day in
 * about four seconds, and the whole batch chain has to fit. Internal reconciliation is one of five
 * steps in that chain, so this suite holds it to <b>two minutes</b> at a million participants — an
 * ordinary nightly close's share of a window, and loose enough that it fails on a regression in the
 * shape of the queries rather than on a busy laptop. The measured duration is logged and asserted
 * from the run row itself, which is where an operator would read it.
 */
@SpringBootTest
class ReconciliationScaleIntegrationTest extends ServiceIntegrationTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ReconciliationScaleIntegrationTest.class);

  /** Overridable for a local run: {@code -Dreconciliation.participants=50000}. */
  private static final int DEFAULT_PARTICIPANTS = 1_000_000;

  private static final int LOAD_CHUNK_ROWS = 200_000;

  private static final Duration WINDOW = Duration.ofMinutes(2);

  @Autowired private ReconciliationService reconciliation;
  @Autowired private JdbcClient jdbc;

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = new FundId(UUID.randomUUID());
  private final long unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
  private final UUID batchId = UUID.randomUUID();
  private String tenantId;

  @Test
  @DisplayName("a million participants reconcile inside the window, with no break and no drift")
  void aMillionParticipantsReconcileInsideTheWindow() {
    int participants = Integer.getInteger("reconciliation.participants", DEFAULT_PARTICIPANTS);
    seedPlatformRows();
    long allotments = loadHoldings(participants);
    long projected = loadProjectedHoldings(participants);
    assertThat(allotments).isEqualTo(participants);
    assertThat(projected).isEqualTo(participants);

    ReconciliationResult result = reconciliation.reconcile();

    FundUnitTie tie = result.tieForLedger(unitLedgerId).orElseThrow();
    assertThat(tie.ties())
        .as(
            "a million holdings, all zero on both sides: a scan this size must not invent drift"
                + " at a page or batch boundary")
        .isTrue();
    assertThat(result.breaksForLedger(unitLedgerId)).isEmpty();
    assertThat(tie.projectionUnits()).isEqualTo(Units.of(0L));

    long durationMillis = result.run().durationMillis().orElseThrow();
    LOGGER.info(
        "internal reconciliation at scale: participants={} holdingAccountsRead={}"
            + " durationMillis={}",
        participants,
        result.run().holdingAccountCount(),
        durationMillis);
    assertThat(result.run().holdingAccountCount())
        .as("two accounts per holding — vested and unvested — for at least this test's own million")
        .isGreaterThanOrEqualTo(2L * participants);
    assertThat(durationMillis)
        .as("the pass must fit the close's window at a million participants")
        .isLessThan(WINDOW.toMillis());
    // Recorded where an operator reads it, not only in this test's own log line.
    assertThat(
            jdbc.sql(
                    "SELECT duration_millis FROM workflow.reconciliation_run WHERE run_id = :runId")
                .param("runId", result.run().runId())
                .query(Long.class)
                .single())
        .isEqualTo(durationMillis);
  }

  /**
   * Removes this run's synthetic rows.
   *
   * <p>The projection side goes by dropping the tenant's own partitions, which is how Task 6.4's
   * ten-million-row fixture cleans up — {@code projection.participant_units} carries a {@code
   * BEFORE DELETE} trigger that raises for every role. The allotment side is a plain {@code DELETE}
   * as the fixture's owning role, which is the same "migration role" the injected-discrepancy
   * criterion itself names. Neither is a path the running service has: {@code fondaria_app} holds
   * no {@code DELETE} on either table.
   *
   * <p>It matters that this happens. Fixtures are reused across runs (AGENTS.md §4), and a million
   * rows left behind would be scanned again by every reconciliation any later test performs.
   */
  @AfterEach
  void removeSyntheticRows() {
    if (tenantId != null) {
      String slug = slugOf(tenantId);
      jdbc.sql("DROP TABLE IF EXISTS projection.participant_units_t_" + slug + " CASCADE").update();
      jdbc.sql("DROP TABLE IF EXISTS projection.transfer_t_" + slug + " CASCADE").update();
      jdbc.sql("DELETE FROM projection.partitioned_tenant WHERE tenant_id = :t")
          .param("t", tenantId)
          .update();
    }
    jdbc.sql("DELETE FROM workflow.allotment WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .update();
    jdbc.sql("DELETE FROM workflow.contribution_batch WHERE batch_id = :batchId")
        .param("batchId", batchId)
        .update();
  }

  // ---- fixture ---------------------------------------------------------------------------

  /** The four rows {@code workflow.allotment}'s foreign keys require, and nothing more. */
  private void seedPlatformRows() {
    jdbc.sql(
            """
            INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                             status, created_at, updated_at)
            VALUES (:organizationId, 'RFC', :taxId, 'Scale Fixture SA de CV', 'ACTIVE', now(), now())
            """)
        .param("organizationId", organizationId.value())
        .param("taxId", freshRfc())
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", unitLedgerId)
        .param("unitScale", Units.SCALE)
        .update();
    jdbc.sql(
            """
            INSERT INTO workflow.contribution_batch
              (batch_id, organization_id, object_key, checksum_sha256, status, submitted_at,
               status_updated_at)
            VALUES (:batchId, :organizationId, :objectKey, :checksum, 'RECEIVED', now(), now())
            """)
        .param("batchId", batchId)
        .param("organizationId", organizationId.value())
        .param("objectKey", "scale-fixture/" + batchId)
        .param("checksum", "0".repeat(64))
        .update();
    tenantId = new TenantRefDeriver(LEDGER_NAMESPACE).derive(organizationId).asUnsignedDecimal();
    jdbc.sql("SELECT projection.ensure_tenant_partitions(:tenantId)")
        .param("tenantId", tenantId)
        .query((rs, rowNum) -> 1)
        .optional();
  }

  /**
   * The candidate set: one distinct {@code (organization, participant, fund)} per participant.
   *
   * <p>{@code units_scaled} is 1 rather than 0 only because {@code allotment_units_positive}
   * forbids zero — nothing reads it. This query supplies keys, never quantities.
   */
  private long loadHoldings(int participants) {
    long navVersionId = strikeANav();
    long inserted = 0;
    for (int loaded = 0; loaded < participants; loaded += LOAD_CHUNK_ROWS) {
      inserted +=
          jdbc.sql(
                  """
                  INSERT INTO workflow.allotment
                    (organization_id, batch_id, participant_internal_ref, source, fund_id,
                     nav_version_id, units_scaled, vested, transfer_id, allotted_at)
                  SELECT :organizationId, :batchId, 'SCALE-' || lpad(g::text, 9, '0'), 'EMPLOYEE',
                         :fundId, :navVersionId, 1, true, gen_random_uuid(), now()
                    FROM generate_series(:fromRow, :toRow) AS g
                  """)
              .param("organizationId", organizationId.value())
              .param("batchId", batchId)
              .param("fundId", fundId.value())
              .param("navVersionId", navVersionId)
              .param("fromRow", loaded)
              .param("toRow", Math.min(loaded + LOAD_CHUNK_ROWS, participants) - 1)
              .update();
    }
    analyze("workflow.allotment");
    return inserted;
  }

  /**
   * The projection's own view of the same participants: one row each, holding nothing.
   *
   * <p>{@code participant_id} is a small integer here; a real one is a SHA-derived u128, so this
   * run's filler cannot collide with an id any other suite derived.
   */
  private long loadProjectedHoldings(int participants) {
    long inserted = 0;
    for (int loaded = 0; loaded < participants; loaded += LOAD_CHUNK_ROWS) {
      inserted +=
          jdbc.sql(
                  """
                  INSERT INTO projection.participant_units
                    (tenant_id, participant_id, fund_id, effective_date, units_scaled,
                     last_transfer_id, last_ledger_timestamp, prev_row_hash, row_hash)
                  SELECT :tenantId, CAST(g AS numeric), :ledgerId, DATE '2026-01-02', 0,
                         CAST(g AS numeric), 1800000000000000000 + g, NULL, '\\x00'::bytea
                    FROM generate_series(:fromRow, :toRow) AS g
                  """)
              .param("tenantId", tenantId)
              .param("ledgerId", unitLedgerId)
              .param("fromRow", loaded)
              .param("toRow", Math.min(loaded + LOAD_CHUNK_ROWS, participants) - 1)
              .update();
    }
    analyze("projection.participant_units_t_" + slugOf(tenantId));
    return inserted;
  }

  private long strikeANav() {
    return jdbc.sql(
            """
            INSERT INTO reference.fund_nav
              (fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, DATE '2026-01-02', 1000000000, 0, 'SCALE_FIXTURE', now())
            RETURNING nav_version_id
            """)
        .param("fundId", fundId.value())
        .query(Long.class)
        .single();
  }

  /** Planner statistics for a table that just grew by a million rows. */
  private void analyze(String table) {
    jdbc.sql("ANALYZE " + table).update();
  }

  private static String slugOf(String tenantId) {
    return org.springframework.util.DigestUtils.md5DigestAsHex(
            tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .substring(0, 16);
  }

  /** A well-formed persona-moral RFC nobody has used. */
  private static String freshRfc() {
    String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    StringBuilder rfc = new StringBuilder(12);
    for (int i = 0; i < 3; i++) {
      rfc.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
    }
    rfc.append("010101");
    for (int i = 0; i < 3; i++) {
      rfc.append(letters.charAt(ThreadLocalRandom.current().nextInt(letters.length())));
    }
    return rfc.toString();
  }
}
