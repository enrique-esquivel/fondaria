package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.testing.ContainerImages;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 11F.1: Real metric names verification against the OpenTelemetry Collector.
 *
 * <p>Spins up the pinned Collector image, pushes sample platform meters through a real {@link
 * OtlpMeterRegistry}, scrapes Prometheus {@code /metrics} from the collector, and asserts that
 * every metric referenced by:
 *
 * <ul>
 *   <li>{@code infrastructure/observability/alerts/financial-control.rules.yml}
 *   <li>{@code infrastructure/observability/alerts/eod-close.rules.yml}
 *   <li>{@code infrastructure/observability/dashboards/financial-control.json}
 *   <li>{@code infrastructure/observability/dashboards/business-overview.json}
 * </ul>
 *
 * actually exists in the scrape output, is a recording rule declared in those files, or is on an
 * explicit allowlist of non-Micrometer sources (e.g. RabbitMQ, CloudNativePG).
 */
@Tag("integration")
class MetricNamesAreRealIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String COLLECTOR_CONFIG =
      """
      receivers:
        otlp:
          protocols:
            http:
              endpoint: 0.0.0.0:4318
      exporters:
        prometheus:
          endpoint: 0.0.0.0:8889
      service:
        pipelines:
          metrics:
            receivers: [otlp]
            exporters: [prometheus]
      """;

  private static final Set<String> PROMQL_KEYWORDS =
      Set.of(
          "sum",
          "rate",
          "irate",
          "increase",
          "delta",
          "min_over_time",
          "abs",
          "max",
          "min",
          "avg",
          "count",
          "histogram_quantile",
          "time",
          "absent",
          "vector",
          "on",
          "ignoring",
          "group_left",
          "group_right",
          "by",
          "without",
          "bool",
          "or",
          "and",
          "unless");

  private static final List<String> NON_MICROMETER_PREFIXES =
      List.of("rabbitmq_detailed_", "cnpg_collector_", "otelcol_");

  @Test
  @DisplayName(
      "All metrics referenced in rules and dashboards are real series emitted by collector")
  void metricNamesAreRealSeriesFromCollector() throws Exception {
    try (GenericContainer<?> collector =
        new GenericContainer<>(ContainerImages.OTEL_COLLECTOR)
            .withCopyToContainer(
                Transferable.of(COLLECTOR_CONFIG), "/etc/otelcol-contrib/config.yaml")
            .withCommand("--config=/etc/otelcol-contrib/config.yaml")
            .withExposedPorts(4318, 8889)
            .waitingFor(Wait.forHttp("/metrics").forPort(8889).forStatusCode(200))) {

      collector.start();

      String otlpUrl =
          "http://" + collector.getHost() + ":" + collector.getMappedPort(4318) + "/v1/metrics";

      OtlpConfig config =
          new OtlpConfig() {
            @Override
            public String get(String key) {
              if ("otlp.url".equals(key)) {
                return otlpUrl;
              }
              if ("otlp.step".equals(key)) {
                return "1s";
              }
              if ("otlp.resourceAttributes".equals(key)) {
                return "service.name=contribution-api";
              }
              return null;
            }
          };

      class TestOtlpMeterRegistry extends OtlpMeterRegistry {
        TestOtlpMeterRegistry(OtlpConfig config, Clock clock) {
          super(config, clock);
        }

        @Override
        public void publish() {
          super.publish();
        }
      }

      TestOtlpMeterRegistry registry = new TestOtlpMeterRegistry(config, Clock.SYSTEM);

      // Configure SLO distribution for http.server.requests
      registry
          .config()
          .meterFilter(
              new MeterFilter() {
                @Override
                public DistributionStatisticConfig configure(
                    Meter.Id id, DistributionStatisticConfig config) {
                  if ("http.server.requests".equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(
                            Duration.ofMillis(50).toNanos(),
                            Duration.ofMillis(250).toNanos(),
                            Duration.ofSeconds(1).toNanos())
                        .build()
                        .merge(config);
                  }
                  return config;
                }
              });

      // Bind JVM metrics
      new JvmMemoryMetrics().bindTo(registry);
      new JvmGcMetrics().bindTo(registry);
      new JvmThreadMetrics().bindTo(registry);
      new ClassLoaderMetrics().bindTo(registry);
      new ProcessorMetrics().bindTo(registry);

      // Register Timers
      Timer timer =
          registry.timer(
              "http.server.requests",
              "status",
              "200",
              "uri",
              "/v1/organizations/{organizationId}/cash-receipts");
      timer.record(Duration.ofMillis(100));
      registry.timer("eod.reconciliation.duration").record(Duration.ofMillis(500));
      registry.timer("eod.custodian.reconciliation.duration").record(Duration.ofMillis(300));

      // Register Counters
      registry.counter("idempotency.conflict.total").increment();
      registry.counter("idempotency.replay.total").increment();
      registry.counter("transfer.rejected.total", "reason", "INSUFFICIENT_UNITS").increment();
      registry.counter("projection.poison.total").increment();
      registry.counter("eod.custodian.breaks.total").increment();
      registry.counter("eod.custodian.statements.late.total").increment();
      registry.counter("eod.snapshot.untied.total").increment();
      registry.counter("eod.reconciliation.breaks.total").increment();

      // Register Gauges
      registry.gauge("ledger.cdc.lag.seconds", Tags.of("group", "projection-worker"), 1.5);
      registry.gauge("projection.drift.minor", 0);
      registry.gauge("eod.hash.chain.valid", 1);
      registry.gauge("eod.reconciliation.blocked", 0);
      registry.gauge("eod.custodian.reconciliation.blocked", 0);
      registry.gauge("eod.signoff.blocked", 0);
      registry.gauge("projection.halted", 0);
      registry.gauge("ledger.head.timestamp", 1000000000L);
      registry.gauge("last.timestamp", Tags.of("group", "projection-worker"), 1000000000L);

      // ADR-0035: projection-worker's ProjectionActivity, registered under its exact meter names
      Tags entriesQueue = Tags.of("queue", "projection.entries.v1.0");
      registry.counter("projection_batches_total", entriesQueue).increment();
      registry.counter("projection_messages_total", entriesQueue).increment(50);
      registry.gauge("projection_queue_owned", entriesQueue, 1);

      // Task 30.10: Business overview telemetry metrics
      Tags sampleFundTags =
          Tags.of(
              "fund_id", "00000000-0000-0000-0000-000000000001",
              "fund_name", "Fondo Ahorro",
              "vehicle_type", "CAJA_DE_AHORRO");
      registry.gauge("fondaria_fund_nav_pesos", sampleFundTags, 10.5);
      registry.gauge("fondaria_fund_units_in_issue", sampleFundTags, 1000.0);
      registry.gauge("fondaria_fund_aum_cents", sampleFundTags, 1050000L);
      registry.gauge("fondaria_fund_aum_pesos", sampleFundTags, 10500.0);
      // Row 26k: contribution-api's enrolment counter replaces the headcount gauge.
      registry.counter("fondaria_participants_enrolled").increment(42);
      registry.gauge("fondaria_business_metrics_snapshot_timestamp_seconds", 1726650000L);
      registry.gauge("break_glass_active_count", 0);

      // Force export
      registry.publish();
      Thread.sleep(2000);

      // Scrape /metrics from collector's Prometheus exporter
      String metricsUrl =
          "http://" + collector.getHost() + ":" + collector.getMappedPort(8889) + "/metrics";
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder().uri(URI.create(metricsUrl)).GET().build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      String scrapeBody = response.body();

      Set<String> scrapedMetricNames = new HashSet<>();
      for (String line : scrapeBody.split("\n")) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int braceIdx = line.indexOf('{');
        int spaceIdx = line.indexOf(' ');
        int endIdx = braceIdx != -1 ? braceIdx : spaceIdx;
        if (endIdx != -1) {
          scrapedMetricNames.add(line.substring(0, endIdx).trim());
        }
      }

      // Assert that key expected metrics were collected
      assertThat(scrapedMetricNames)
          .contains(
              "http_server_requests_milliseconds_bucket",
              "http_server_requests_milliseconds_count",
              "http_server_requests_milliseconds_sum",
              "jvm_memory_used_bytes",
              "ledger_cdc_lag_seconds",
              "projection_drift_minor",
              "eod_hash_chain_valid",
              "idempotency_conflict_total",
              "projection_messages_total",
              "projection_queue_owned",
              "fondaria_fund_nav_pesos",
              "fondaria_fund_units_in_issue",
              "fondaria_fund_aum_pesos",
              "fondaria_participants_enrolled_total",
              "fondaria_business_metrics_snapshot_timestamp_seconds");

      // Parse rules and dashboard files
      Path repoRoot = Path.of(RepositoryRoot.path(""));
      Path fcRulesPath =
          repoRoot.resolve("infrastructure/observability/alerts/financial-control.rules.yml");
      Path eodRulesPath =
          repoRoot.resolve("infrastructure/observability/alerts/eod-close.rules.yml");
      Path dashboardPath =
          repoRoot.resolve("infrastructure/observability/dashboards/financial-control.json");
      Path businessDashboardPath =
          repoRoot.resolve("infrastructure/observability/dashboards/business-overview.json");

      Set<String> recordingRules = new HashSet<>();
      List<ReferencedExpression> expressions = new ArrayList<>();

      parseRuleFile(fcRulesPath, recordingRules, expressions);
      parseRuleFile(eodRulesPath, recordingRules, expressions);
      parseDashboardFile(dashboardPath, expressions);
      parseDashboardFile(businessDashboardPath, expressions);

      // Validate all referenced metric names
      for (ReferencedExpression expr : expressions) {
        Set<String> metricsInExpr = extractMetricNames(expr.expression());
        for (String metric : metricsInExpr) {
          boolean isScraped = scrapedMetricNames.contains(metric);
          boolean isRecordingRule = recordingRules.contains(metric);
          boolean isNonMicrometer = NON_MICROMETER_PREFIXES.stream().anyMatch(metric::startsWith);

          if (!isScraped && !isRecordingRule && !isNonMicrometer) {
            fail(
                "Unrecognized or un-emitted metric '%s' in file %s:\n  Expression: %s\n  (Not in collector scrape, not a recording rule, and not an allowed non-Micrometer source)",
                metric, expr.file(), expr.expression());
          }
        }
      }
    }
  }

  record ReferencedExpression(String file, String expression) {}

  private static void parseRuleFile(
      Path ruleFile, Set<String> recordingRules, List<ReferencedExpression> expressions)
      throws Exception {
    Yaml yaml = new Yaml();
    Map<String, Object> root;
    try (InputStream is = Files.newInputStream(ruleFile)) {
      root = yaml.load(is);
    }
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
    if (groups == null) return;

    for (Map<String, Object> group : groups) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
      if (rules == null) continue;
      for (Map<String, Object> rule : rules) {
        String record = (String) rule.get("record");
        if (record != null) {
          recordingRules.add(record.trim());
        }
        String expr = (String) rule.get("expr");
        if (expr != null) {
          expressions.add(new ReferencedExpression(ruleFile.getFileName().toString(), expr.trim()));
        }
      }
    }
  }

  private static void parseDashboardFile(Path dashboardPath, List<ReferencedExpression> expressions)
      throws Exception {
    JsonNode root;
    try (InputStream is = Files.newInputStream(dashboardPath)) {
      root = MAPPER.readTree(is);
    }
    JsonNode panels = root.get("panels");
    if (panels == null || !panels.isArray()) return;

    for (JsonNode panel : panels) {
      JsonNode targets = panel.get("targets");
      if (targets != null && targets.isArray()) {
        for (JsonNode target : targets) {
          JsonNode expr = target.get("expr");
          if (expr != null && !expr.isNull()) {
            expressions.add(
                new ReferencedExpression(
                    dashboardPath.getFileName().toString(), expr.asText().trim()));
          }
        }
      }
    }
  }

  static Set<String> extractMetricNames(String promql) {
    // 1. Remove strings inside quotes: "..."
    String sanitized = promql.replaceAll("\"[^\"]*\"", " ");

    // 2. Remove label selectors: {...}
    sanitized = sanitized.replaceAll("\\{[^}]*\\}", " ");

    // 3. Remove range duration selectors: [...]
    sanitized = sanitized.replaceAll("\\[[^]]*\\]", " ");

    // 4. Remove 'by (...)' and 'without (...)' aggregation clauses
    sanitized = sanitized.replaceAll("\\b(by|without)\\s*\\([^)]*\\)", " ");

    // 5. Tokenize identifiers
    Pattern identPattern = Pattern.compile("\\b[a-zA-Z_:][a-zA-Z0-9_:]*\\b");
    Matcher matcher = identPattern.matcher(sanitized);

    Set<String> result = new HashSet<>();
    while (matcher.find()) {
      String token = matcher.group();

      // Skip numbers / scientific notation (e.g. 1e9, 100, 0)
      if (token.matches("^[0-9]+[a-zA-Z0-9]*$")
          || token.matches("^[0-9]+e[0-9]+$")
          || token.equals("1e9")) {
        continue;
      }
      // Skip durations like 5m, 1h, 30s, 10m
      if (token.matches("^[0-9]+[smhdwy]$")) {
        continue;
      }
      // Skip PromQL keywords and functions
      if (PROMQL_KEYWORDS.contains(token.toLowerCase())) {
        continue;
      }

      result.add(token);
    }
    return result;
  }
}
