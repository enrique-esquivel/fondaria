package dev.fondaria.eodjob.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The plan PostgreSQL chooses for a statement over {@code workflow.allotment}, taken against an
 * allotment history shaped like a close's, as a flat list of plan nodes.
 *
 * <h2>Why the history, and why it is rolled back</h2>
 *
 * <p>A plan is a cost decision, and costs come from statistics. The shared fixture's table holds
 * whatever earlier runs left behind (docs/ENGINEERING-RULES.md §4) — usually a few dozen rows — and
 * at that size the planner honestly prefers seeking {@code organization_id} and sorting the handful
 * of survivors, which says nothing about the table a close runs against. So each plan is taken
 * inside one transaction that first writes {@value #ORGANIZATIONS} fresh organizations, each with
 * {@value #PARTICIPANTS} participants allotted on each of {@value #BATCHES} days, runs {@code
 * ANALYZE}, takes the plan under the planner's ordinary settings, and rolls everything back.
 * Nothing is overridden: the assertion is about the plan production would choose.
 *
 * <p>Several organizations, not one, because the shape is what the planner costs. With a single
 * organization holding the whole table, "this organization" is every row and a sequential scan is
 * the right answer; the demo has many organizations each holding a slice, and a page is a small
 * range inside one of them.
 *
 * <p>{@code reference.fund_nav} is analyzed too, because the cost-basis read joins it. Left stale,
 * its histogram ends below the NAV the seeded lots name, the planner concludes a merge join on
 * {@code nav_version_id} stops after a handful of rows, and walks {@code allotment_by_nav_version}
 * instead — a plan about the fixture's statistics, not about the index under test.
 *
 * <p>The rows and their statistics roll back with the transaction. {@code ANALYZE}'s update of
 * {@code pg_class.reltuples} is in place and does not; that only biases the next estimate on a
 * shared test table until autovacuum recomputes it, and no other test asserts on a plan.
 */
public final class QueryPlans {

  /** Organizations in the seeded history. */
  public static final int ORGANIZATIONS = 10;

  /** Participants in each seeded organization; refs run {@code EMP-000001} upwards. */
  public static final int PARTICIPANTS = 2_000;

  /** Days of history: one batch, and so one allotment row per participant, each. */
  public static final int BATCHES = 10;

  private static final ObjectMapper JSON = new ObjectMapper();

  private QueryPlans() {}

  /**
   * One of the seeded organizations, and the fund every seeded lot is in, for binding a statement
   * to a page of the history.
   */
  public record History(UUID organizationId, UUID fundId) {}

  /**
   * Every node, depth-first, of the plan for {@code sql} over a seeded history, bound to the
   * parameters {@code params} derives from it.
   */
  public static List<JsonNode> overHistory(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      String sql,
      Function<History, Map<String, ?>> params) {
    String plan =
        transactions.execute(
            status -> {
              status.setRollbackOnly();
              History history = seed(jdbc);
              jdbc.sql("ANALYZE workflow.allotment").update();
              jdbc.sql("ANALYZE reference.fund_nav").update();
              return jdbc.sql("EXPLAIN (FORMAT JSON) " + sql)
                  .params(params.apply(history))
                  .query(String.class)
                  .single();
            });
    try {
      List<JsonNode> nodes = new ArrayList<>();
      collect(JSON.readTree(plan).get(0).get("Plan"), nodes);
      return nodes;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("EXPLAIN returned something other than JSON", e);
    }
  }

  /** The node types in the plan, for an assertion that names what it found. */
  public static List<String> nodeTypes(List<JsonNode> nodes) {
    return nodes.stream().map(node -> node.get("Node Type").asText()).toList();
  }

  /** The one node scanning {@code indexName}; fails if the plan does not use that index. */
  public static JsonNode scanOf(List<JsonNode> nodes, String indexName) {
    return nodes.stream()
        .filter(node -> indexName.equals(node.path("Index Name").asText()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no scan of " + indexName + " in the plan; nodes were " + describe(nodes)));
  }

  private static History seed(JdbcClient jdbc) {
    List<UUID> organizations = new ArrayList<>(ORGANIZATIONS);
    for (int i = 0; i < ORGANIZATIONS; i++) {
      organizations.add(UUID.randomUUID());
    }
    History history = new History(organizations.get(ORGANIZATIONS / 2), UUID.randomUUID());
    for (UUID organizationId : organizations) {
      jdbc.sql(
              """
              INSERT INTO master.organization (organization_id, tax_id_scheme, tax_id, legal_name,
                                               status, created_at, updated_at)
              VALUES (:organizationId, 'RFC', :taxId, 'Query Plans SA de CV', 'ACTIVE', now(),
                      now())
              """)
          .param("organizationId", organizationId)
          .param("taxId", freshRfc())
          .update();
      jdbc.sql(
              """
              INSERT INTO workflow.contribution_batch
                (batch_id, organization_id, object_key, checksum_sha256, status, submitted_at,
                 status_updated_at)
              SELECT gen_random_uuid(), :organizationId, 'query-plans/' || gen_random_uuid(),
                     repeat('0', 64), 'ALLOTTED', now(), now()
                FROM generate_series(1, :batches)
              """)
          .param("organizationId", organizationId)
          .param("batches", BATCHES)
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 8, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", history.fundId())
        .param("unitLedgerId", ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L))
        .update();
    long navVersionId =
        jdbc.sql(
                """
                INSERT INTO reference.fund_nav
                  (fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
                VALUES (:fundId, DATE '2026-03-02', 100000000, 0, 'TEST', now())
                RETURNING nav_version_id
                """)
            .param("fundId", history.fundId())
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO workflow.allotment
              (organization_id, batch_id, participant_internal_ref, source, fund_id,
               nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            SELECT b.organization_id, b.batch_id, 'EMP-' || lpad(p::text, 6, '0'), 'EMPLOYEE',
                   :fundId, :navVersionId, 1, true, gen_random_uuid(), now() - interval '1 day'
              FROM workflow.contribution_batch b, generate_series(1, :participants) p
             WHERE b.organization_id IN (:organizations)
            """)
        .param("organizations", organizations)
        .param("fundId", history.fundId())
        .param("navVersionId", navVersionId)
        .param("participants", PARTICIPANTS)
        .update();
    return history;
  }

  private static String describe(List<JsonNode> nodes) {
    return nodes.stream()
        .map(node -> node.get("Node Type").asText() + " " + node.path("Index Name").asText(""))
        .toList()
        .toString();
  }

  private static void collect(JsonNode node, List<JsonNode> into) {
    into.add(node);
    for (JsonNode child : node.path("Plans")) {
      collect(child, into);
    }
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
