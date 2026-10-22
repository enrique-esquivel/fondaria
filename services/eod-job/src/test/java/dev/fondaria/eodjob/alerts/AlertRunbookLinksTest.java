package dev.fondaria.eodjob.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Task 7.6's "every alert links to a runbook that exists" acceptance criterion, checked the same
 * way {@code ContainerImagesTest} checks image tags against {@code docker-compose.yml}: parse the
 * real file and assert against the real filesystem, so a typo'd path fails a normal {@code mvn
 * verify} rather than only being caught by someone reading the YAML.
 *
 * <p>No Docker needed — unlike {@link EodCloseAlertRulesIntegrationTest}, which asks promtool
 * itself whether the rules are valid PromQL, this only asks whether every {@code runbook_url}
 * annotation resolves to a file that exists.
 */
class AlertRunbookLinksTest {

  @Test
  void everyAlertHasARunbookUrlThatResolvesToAnExistingFile() throws Exception {
    Path repoRoot = RepoRoot.find();
    Path rulesFile = repoRoot.resolve("infrastructure/observability/alerts/eod-close.rules.yml");
    assertThat(rulesFile).exists();

    Yaml yaml = new Yaml();
    Map<String, Object> root;
    try (InputStream is = Files.newInputStream(rulesFile)) {
      root = yaml.load(is);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
    assertThat(groups).isNotEmpty();

    // Actual alert name -> its runbook_url, in file order. A Map (not just a name list) is the
    // point: swapping two runbook_urls between two alerts still passes a check that only verifies
    // "every alert has some existing runbook_url" and "the alert-name set is right" separately.
    Map<String, String> actual = new TreeMap<>();
    List<String> alertNames = new ArrayList<>();
    for (Map<String, Object> group : groups) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
      for (Map<String, Object> rule : rules) {
        String alertName = (String) rule.get("alert");
        if (alertName == null) {
          // A recording rule, not an alert -- nothing for this test to check. None exist in
          // eod-close.rules.yml today, but a future addition should not need this test rewritten.
          continue;
        }
        alertNames.add(alertName);

        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
        assertThat(annotations)
            .as("alert %s must annotate a runbook_url", alertName)
            .containsKey("runbook_url");

        String runbookUrl = (String) annotations.get("runbook_url");
        String runbookFile = runbookUrl.substring(0, runbookUrl.indexOf('#'));
        String runbookAnchor = runbookUrl.substring(runbookUrl.indexOf('#') + 1);
        assertThat(repoRoot.resolve(runbookFile))
            .as("alert %s's runbook_url (%s) must exist", alertName, runbookUrl)
            .exists();
        assertThat(Files.readString(repoRoot.resolve(runbookFile)))
            .as("alert %s's runbook_url (%s) must name an existing section", alertName, runbookUrl)
            .contains("<a id=\"" + runbookAnchor + "\"></a>");
        actual.put(alertName, runbookUrl);
      }
    }

    // Task 7.6 names five runbooks, one per failure mode; this pins each alert to its own,
    // specific runbook, not merely "some file that exists" -- so a copy-paste that pointed two
    // alerts at the same runbook, or swapped two runbook_urls between alerts, fails here.
    Map<String, String> expected =
        Map.of(
            "EODInternalReconciliationDrift", "docs/RUNBOOKS.md#internal-reconciliation-drift",
            "EODCustodianBreak", "docs/RUNBOOKS.md#custodian-break",
            "EODCustodianStatementLate", "docs/RUNBOOKS.md#late-custodian-file",
            "EODSnapshotTieOutFailure", "docs/RUNBOOKS.md#snapshot-tie-out-failure",
            "EODMissedSignoff", "docs/RUNBOOKS.md#missed-signoff");
    assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    assertThat(alertNames).containsExactlyInAnyOrderElementsOf(expected.keySet());
  }
}
