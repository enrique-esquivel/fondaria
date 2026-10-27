package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Task 11.3: structural test verifying {@code
 * infrastructure/observability/dashboards/financial-control.json}.
 *
 * <p>Validates dashboard syntax, Grafana provisioning requirements (uid, non-editable, prometheus
 * datasource), and checks that every panel required by Task 11.3 is configured with its target
 * metric expressions.
 */
class FinancialControlDashboardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("financial-control.json exists, parses, and meets all Task 11.3 panel requirements")
  void dashboardStructureAndRequiredPanels() throws Exception {
    Path dashboardPath =
        Path.of(
            RepositoryRoot.path("infrastructure/observability/dashboards/financial-control.json"));
    assertThat(dashboardPath).exists();

    JsonNode root;
    try (InputStream is = Files.newInputStream(dashboardPath)) {
      root = MAPPER.readTree(is);
    }

    assertThat(root.get("uid").asText()).isEqualTo("financial-control");
    assertThat(root.get("title").asText()).contains("Financial Control");
    assertThat(root.get("editable").asBoolean()).isFalse();
    assertThat(root.get("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);
    assertThat(root.get("refresh").asText()).isEqualTo("10s");

    JsonNode panels = root.get("panels");
    assertThat(panels).isNotNull();
    assertThat(panels.isArray()).isTrue();
    assertThat(panels.size()).isGreaterThanOrEqualTo(8);

    List<String> allExpressions = new ArrayList<>();
    for (JsonNode panel : panels) {
      JsonNode datasource = panel.get("datasource");
      assertThat(datasource).isNotNull();
      assertThat(datasource.get("uid").asText()).isEqualTo("prometheus");

      JsonNode targets = panel.get("targets");
      assertThat(targets).isNotNull();
      assertThat(targets.isArray()).isTrue();
      for (JsonNode target : targets) {
        String expr = target.get("expr").asText();
        allExpressions.add(expr);
        assertThat(target.get("datasource").get("uid").asText()).isEqualTo("prometheus");
      }
    }

    // Required panels:
    // 1. CDC lag
    assertThat(allExpressions).anyMatch(expr -> expr.contains("ledger_cdc_lag_seconds"));

    // 2. Projection drift (total and worst account)
    assertThat(allExpressions).anyMatch(expr -> expr.contains("projection_drift_minor"));
    assertThat(allExpressions).anyMatch(expr -> expr.contains("eod_reconciliation_blocked"));

    // 3. EOD hash chain validity
    assertThat(allExpressions).anyMatch(expr -> expr.contains("eod_hash_chain_valid"));

    // 4. DLQ depth & poison messages
    assertThat(allExpressions).anyMatch(expr -> expr.contains("dlq_depth"));
    assertThat(allExpressions).anyMatch(expr -> expr.contains("projection_poison_total"));

    // 5. Transfer rejection rate by reason
    assertThat(allExpressions)
        .anyMatch(expr -> expr.contains("transfer_rejected_total") && expr.contains("reason"));

    // 6. Idempotency conflicts
    assertThat(allExpressions).anyMatch(expr -> expr.contains("idempotency_conflict_total"));

    // 7. Reconciliation run status and duration
    assertThat(allExpressions).anyMatch(expr -> expr.contains("eod_reconciliation_duration"));
    assertThat(allExpressions).anyMatch(expr -> expr.contains("eod_reconciliation_blocked"));

    // 8. The close and what it produced: sign-offs and statements, so a demo or an operator can see
    // that the daily close ran without reading workflow.close_signoff.
    assertThat(allExpressions).anyMatch(expr -> expr.contains("increase(eod_signoff_total["));
    assertThat(allExpressions).anyMatch(expr -> expr.contains("eod_signoff_blocked"));
    assertThat(allExpressions)
        .anyMatch(expr -> expr.contains("increase(statement_rendered_total["));
    assertThat(allExpressions)
        .anyMatch(expr -> expr.contains("increase(statement_runs_completed_total["));

    // 9. Ledger head vs projection head
    assertThat(allExpressions).anyMatch(expr -> expr.contains("ledger_head_timestamp"));
    assertThat(allExpressions).anyMatch(expr -> expr.contains("last_timestamp"));
    // Ledger timestamps are nanoseconds and dateTimeAsIso takes milliseconds: "/ 1e9" drew every
    // head in January 1970. A pod that has seen nothing reports 0, so heads are filtered "> 0".
    assertThat(allExpressions)
        .contains("(ledger_head_timestamp > 0) / 1e6", "(last_timestamp > 0) / 1e6")
        .anyMatch(expr -> expr.contains("min(last_timestamp > 0)"));

    // Verify grid layout validity: within 24-col grid and no overlapping panel bounds
    record Rect(int id, int x1, int y1, int x2, int y2) {
      boolean overlaps(Rect other) {
        return this.x1 < other.x2 && this.x2 > other.x1 && this.y1 < other.y2 && this.y2 > other.y1;
      }
    }
    List<Rect> panelRects = new ArrayList<>();
    for (JsonNode panel : panels) {
      JsonNode gridPos = panel.get("gridPos");
      assertThat(gridPos).isNotNull();
      int x = gridPos.get("x").asInt();
      int y = gridPos.get("y").asInt();
      int w = gridPos.get("w").asInt();
      int h = gridPos.get("h").asInt();
      int id = panel.get("id").asInt();

      assertThat(x).isGreaterThanOrEqualTo(0);
      assertThat(w).isGreaterThan(0);
      assertThat(h).isGreaterThan(0);
      assertThat(x + w).isLessThanOrEqualTo(24);

      Rect current = new Rect(id, x, y, x + w, y + h);
      for (Rect existing : panelRects) {
        assertThat(current.overlaps(existing))
            .as("Panel %d overlaps with Panel %d", current.id(), existing.id())
            .isFalse();
      }
      panelRects.add(current);
    }
  }
}
