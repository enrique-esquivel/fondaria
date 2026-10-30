package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fondaria.eodjob.support.QueryPlans;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The distinct-holdings keyset walk is a bounded index walk: one seek on the whole row comparison,
 * then index order, with no sort.
 *
 * <p>Load run 8 (2026-10-29) is why this is asserted rather than assumed. Task 7.4 put the
 * participant reference under {@code COLLATE "C"}, the only index was built under the default
 * collation, and the planner quietly fell back to seeking on {@code organization_id} alone,
 * filtering the row comparison and sorting on disk — every page re-reading its organization's whole
 * allotment history. Every ordering test kept passing, because the results were still right; only
 * the plan shows it.
 */
@SpringBootTest
class JdbcHoldingAccountSourcePlanIntegrationTest extends ServiceIntegrationTest {

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("a next page seeks on the whole row comparison and needs no sort")
  void nextPageIsABoundedIndexWalk() {
    // A cursor in the middle of the seeded organization: the case run 8 got wrong, where
    // everything before the cursor in the same organization was read and thrown away.
    List<JsonNode> plan =
        QueryPlans.overHistory(
            jdbc,
            transactions,
            JdbcHoldingAccountSource.NEXT_PAGE,
            history ->
                Map.of(
                    "afterOrganizationId",
                    history.organizationId(),
                    "afterParticipantRef",
                    "EMP-001000",
                    "afterFundId",
                    history.fundId(),
                    "limit",
                    400));

    JsonNode scan = QueryPlans.scanOf(plan, "allotment_by_holding_c");
    assertThat(scan.get("Node Type").asText())
        .as("the three columns are the whole projection")
        .isEqualTo("Index Only Scan");
    assertThat(scan.get("Index Cond").asText())
        .as("the row comparison is the seek, not a filter applied after seeking the organization")
        .startsWith("(ROW(organization_id")
        .contains(") > ROW(");
    assertThat(scan.has("Filter")).as("nothing left to filter after the seek").isFalse();
    assertThat(QueryPlans.nodeTypes(plan))
        .as("index order is already the page's order")
        .doesNotContain("Sort", "Incremental Sort");
  }
}
