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
 * Task 30.10: structural test verifying {@code
 * infrastructure/observability/dashboards/business-overview.json}.
 *
 * <p>Validates dashboard syntax, Grafana provisioning requirements (uid, non-editable, prometheus
 * datasource), non-overlapping grid layout, and checks that every panel required by Task 30.10 is
 * configured with its target metric expressions.
 */
class BusinessOverviewDashboardTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("business-overview.json exists, parses, and meets all Task 30.10 requirements")
  void dashboardStructureAndRequiredPanels() throws Exception {
    Path dashboardPath =
        Path.of(
            RepositoryRoot.path("infrastructure/observability/dashboards/business-overview.json"));
    assertThat(dashboardPath).exists();

    JsonNode root;
    try (InputStream is = Files.newInputStream(dashboardPath)) {
      root = MAPPER.readTree(is);
    }

    assertThat(root.get("uid").asText()).isEqualTo("business-overview");
    assertThat(root.get("title").asText()).contains("Business Overview");
    assertThat(root.get("editable").asBoolean()).isFalse();
    assertThat(root.get("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);
    assertThat(root.get("refresh").asText()).isEqualTo("10s");

    JsonNode panels = root.get("panels");
    assertThat(panels).isNotNull();
    assertThat(panels.isArray()).isTrue();
    assertThat(panels.size()).isGreaterThanOrEqualTo(8);

    List<String> allExpressions = new ArrayList<>();
    boolean hasNoticePanel = false;

    for (JsonNode panel : panels) {
      JsonNode datasource = panel.get("datasource");
      assertThat(datasource).isNotNull();
      assertThat(datasource.get("uid").asText()).isEqualTo("prometheus");

      String type = panel.get("type").asText();
      if ("text".equals(type)) {
        JsonNode options = panel.get("options");
        if (options != null && options.has("content")) {
          String content = options.get("content").asText();
          if (content.contains("non-authoritative") || content.contains("UNITS_IN_ISSUE")) {
            hasNoticePanel = true;
          }
        }
      }

      JsonNode targets = panel.get("targets");
      if (targets != null && targets.isArray()) {
        for (JsonNode target : targets) {
          String expr = target.get("expr").asText();
          allExpressions.add(expr);
          assertThat(target.get("datasource").get("uid").asText()).isEqualTo("prometheus");
        }
      }
    }

    // Required non-authoritative operational notice
    assertThat(hasNoticePanel)
        .as(
            "Dashboard must contain an operational notice panel declaring non-authoritative telemetry")
        .isTrue();

    // Required metrics:
    // 1. Total & per-fund AUM in pesos
    assertThat(allExpressions).anyMatch(expr -> expr.contains("fondaria_fund_aum_pesos"));

    // 2. Units in issue
    assertThat(allExpressions).anyMatch(expr -> expr.contains("fondaria_fund_units_in_issue"));

    // 3. Participants enrolled in the range -- row 26k: a counter read through increase(), never
    // the raw value (it is per pod and restarts at zero); the 15 s headcount gauge is gone.
    assertThat(allExpressions)
        .anyMatch(expr -> expr.contains("increase(fondaria_participants_enrolled_total["));
    assertThat(allExpressions).noneMatch(expr -> expr.contains("fondaria_fund_participants_count"));

    // Row 26n: balance-api runs several replicas (HPA), each publishing the same fund-wide gauge
    // under its own instance label, so every expression must collapse pods before summing.
    for (String expr : allExpressions) {
      if (expr.matches(".*fondaria_fund_(aum|units|nav)_.*")) {
        assertThat(expr)
            .as("dedupe pods with max by (fund_id ...): %s", expr)
            .contains("max by (fund_id");
      }
    }

    // Grafana has no built-in MXN unit: "currencyMXN" rendered as the raw number with the unit's
    // name appended. Pesos panels use the custom-currency form instead.
    for (JsonNode panel : panels) {
      if (panel.path("targets").toString().contains("_pesos")) {
        assertThat(panel.path("fieldConfig").path("defaults").path("unit").asText())
            .as("unit of panel %s", panel.path("title").asText())
            .isEqualTo("currency:MX$");
      }
    }

    // 4. Telemetry snapshot age
    assertThat(allExpressions)
        .anyMatch(expr -> expr.contains("fondaria_business_metrics_snapshot_timestamp_seconds"));

    // 5. Fund NAV over time
    assertThat(allExpressions).anyMatch(expr -> expr.contains("fondaria_fund_nav_pesos"));

    // 6. Distribution by vehicle type
    assertThat(allExpressions)
        .anyMatch(
            expr -> expr.contains("vehicle_type") && expr.contains("fondaria_fund_aum_pesos"));

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
