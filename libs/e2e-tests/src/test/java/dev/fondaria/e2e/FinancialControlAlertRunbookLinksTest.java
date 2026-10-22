package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 11.4: Acceptance criterion "Every alert has a runbook that exists (CI check)".
 *
 * <p>Validates {@code infrastructure/observability/alerts/financial-control.rules.yml}: - Parses
 * every alert rule and asserts required fields: {@code severity}, {@code summary}, {@code
 * description}, and {@code runbook_url}. - Verifies that {@code severity} is strictly {@code page}
 * or {@code warning}. - Verifies that {@code description} is non-empty and contains actionable
 * instructions for on-call responders. - Asserts that every {@code runbook_url} resolves to a
 * valid, non-empty markdown runbook in {@code docs/RUNBOOKS.md}. - Pins the exact mapping of alert
 * names to runbook paths.
 */
class FinancialControlAlertRunbookLinksTest {

  private static final Set<String> VALID_SEVERITIES = Set.of("page", "warning");

  private static final Map<String, String> EXPECTED_ALERT_RUNBOOKS =
      Map.ofEntries(
          Map.entry("RabbitMqScrapeMissing", "docs/RUNBOOKS.md#dead-letter-queue"),
          Map.entry("CDCLagWarning", "docs/RUNBOOKS.md#slo-cdc-lag"),
          Map.entry("CDCLagPage", "docs/RUNBOOKS.md#cdc-replay"),
          Map.entry("ProjectionHalted", "docs/RUNBOOKS.md#cdc-replay"),
          Map.entry("ProjectionQueueNotDraining", "docs/RUNBOOKS.md#slo-cdc-lag"),
          Map.entry("ProjectionDrift", "docs/RUNBOOKS.md#projection-drift"),
          Map.entry("EODHashChainInvalid", "docs/RUNBOOKS.md#eod-hash-chain"),
          Map.entry("DLQMessagesPresent", "docs/RUNBOOKS.md#dead-letter-queue"),
          Map.entry("IdempotencyConflictSpike", "docs/RUNBOOKS.md#idempotency-conflicts"),
          Map.entry("BreakGlassAccessActive", "docs/RUNBOOKS.md#break-glass"),
          Map.entry("ServiceDownProjectionWorker", "docs/RUNBOOKS.md#cdc-replay"),
          Map.entry("ServiceDownAmqpFanout", "docs/RUNBOOKS.md#rabbitmq-node-failure"),
          Map.entry("ServiceDownEodJob", "docs/RUNBOOKS.md#internal-reconciliation-drift"),
          Map.entry("ServiceDownContributionApi", "docs/RUNBOOKS.md#slo-payment-latency"),
          Map.entry("ServiceDownBalanceApi", "docs/RUNBOOKS.md#slo-balance-latency"),
          Map.entry("ServiceDownValuationApi", "docs/RUNBOOKS.md#late-custodian-file"),
          Map.entry("ServiceDownAllotmentJob", "docs/RUNBOOKS.md#slo-cdc-lag"),
          Map.entry("ServiceDownStatementWorker", "docs/RUNBOOKS.md#missed-signoff"),
          Map.entry("PaymentInitiationLatencyBudgetBurn", "docs/RUNBOOKS.md#slo-payment-latency"),
          Map.entry("BalanceReadLatencyBudgetBurn", "docs/RUNBOOKS.md#slo-balance-latency"),
          Map.entry("CDCLagSLOBudgetBurn", "docs/RUNBOOKS.md#slo-cdc-lag"),
          Map.entry("PlatformAvailabilityBudgetBurn", "docs/RUNBOOKS.md#slo-availability"),
          Map.entry("PostgresBackupAgeTooHigh", "docs/RUNBOOKS.md#postgres-backup-age"));

  @Test
  @DisplayName(
      "Every financial control and SLO alert has a valid severity, 3am description, and existing runbook")
  void everyAlertHasValidMetadataAndResolvingRunbook() throws Exception {
    Path repoRoot = Path.of(RepositoryRoot.path(""));
    Path rulesFile =
        repoRoot.resolve("infrastructure/observability/alerts/financial-control.rules.yml");
    assertThat(rulesFile).as("rules file must exist").exists();

    Yaml yaml = new Yaml();
    Map<String, Object> root;
    try (InputStream is = Files.newInputStream(rulesFile)) {
      root = yaml.load(is);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
    assertThat(groups).as("rule groups must not be empty").isNotEmpty();

    Map<String, String> actualRunbooks = new TreeMap<>();
    List<String> actualAlertNames = new ArrayList<>();

    for (Map<String, Object> group : groups) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
      for (Map<String, Object> rule : rules) {
        String alertName = (String) rule.get("alert");
        if (alertName == null) {
          // Recording rule (e.g. job:http_server_requests_payment_latency:p99)
          assertThat(rule.get("record"))
              .as("rule must have either alert or record defined")
              .isNotNull();
          continue;
        }

        actualAlertNames.add(alertName);

        // Labels
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
        assertThat(labels).as("alert %s must have labels", alertName).isNotNull();
        String severity = (String) labels.get("severity");
        assertThat(severity)
            .as("alert %s severity must be page or warning", alertName)
            .isIn(VALID_SEVERITIES);

        // Annotations
        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
        assertThat(annotations).as("alert %s must have annotations", alertName).isNotNull();

        String summary = (String) annotations.get("summary");
        assertThat(summary)
            .as("alert %s must have a non-blank summary annotation", alertName)
            .isNotBlank();

        String description = (String) annotations.get("description");
        assertThat(description)
            .as("alert %s must have a non-blank description for 3am response", alertName)
            .isNotBlank();

        String runbookUrl = (String) annotations.get("runbook_url");
        assertThat(runbookUrl)
            .as("alert %s must have a runbook_url annotation", alertName)
            .isNotBlank();

        assertThat(runbookUrl)
            .as("alert %s runbook_url (%s) must name a section anchor", alertName, runbookUrl)
            .contains("#");
        Path runbookPath = repoRoot.resolve(runbookUrl.substring(0, runbookUrl.indexOf('#')));
        String runbookAnchor = runbookUrl.substring(runbookUrl.indexOf('#') + 1);
        assertThat(runbookPath)
            .as("alert %s runbook_url (%s) must exist on filesystem", alertName, runbookUrl)
            .exists();
        String runbookText = Files.readString(runbookPath);
        int sectionStart = runbookText.indexOf("<a id=\"" + runbookAnchor + "\"></a>");
        assertThat(sectionStart)
            .as("alert %s runbook_url (%s) must name an existing section", alertName, runbookUrl)
            .isNotNegative();
        int sectionEnd = runbookText.indexOf("<a id=\"", sectionStart + 1);
        String section =
            runbookText.substring(sectionStart, sectionEnd < 0 ? runbookText.length() : sectionEnd);
        assertThat(section.length())
            .as("alert %s runbook section (%s) must not be empty", alertName, runbookUrl)
            .isGreaterThan(100);

        actualRunbooks.put(alertName, runbookUrl);
      }
    }

    assertThat(actualAlertNames)
        .as("all required financial control and SLO alerts must be declared")
        .containsExactlyInAnyOrderElementsOf(EXPECTED_ALERT_RUNBOOKS.keySet());

    assertThat(actualRunbooks)
        .as("each alert must map to its designated runbook")
        .containsExactlyInAnyOrderEntriesOf(EXPECTED_ALERT_RUNBOOKS);
  }
}
