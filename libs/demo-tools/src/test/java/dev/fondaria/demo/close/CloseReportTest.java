package dev.fondaria.demo.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CloseReportTest {

  private static final String MANIFEST_SHA = "a".repeat(64);
  private static final String PREVIOUS_SHA = "b".repeat(64);

  private static List<String> row(String announced, long passes, long failures) {
    return List.of(
        "2026-10-27",
        "2026-10-27 17:05:31",
        announced,
        "11111111-1111-1111-1111-111111111111",
        "PASSED",
        "3",
        "120000",
        "52",
        "0",
        "4200",
        "22222222-2222-2222-2222-222222222222",
        "PASSED",
        "0",
        "0",
        "0",
        "33333333-3333-3333-3333-333333333333",
        "COMPLETED",
        "60000",
        "3",
        "snapshots/dt=2026-10-27/positions.ndjson.gz",
        "snapshots/dt=2026-10-27/manifest.json",
        MANIFEST_SHA,
        Long.toString(passes),
        Long.toString(failures));
  }

  private static final HashChainCheck CHAIN =
      new HashChainCheck(5, 0, "", "2026-10-26", PREVIOUS_SHA);

  @Test
  @DisplayName("renders the sign-off with the exact runs and objects it cites")
  void rendersTheSignoff() {
    String out =
        String.join("\n", CloseReport.fromRow(row("t", 1, 0)).render("eod-snapshots", CHAIN));

    assertThat(out)
        .contains("Daily close 2026-10-27: SIGNED OFF at 2026-10-27 17:05:31")
        .contains("close.signed-off.v1 published")
        .contains("Internal reconciliation   PASSED")
        .contains("3 funds, 120,000 holding accounts, 52 cash accounts, 0 breaks, 4.2 s")
        .contains("Dense snapshot            COMPLETED")
        .contains("60,000 positions across 3 funds")
        .contains("s3://eod-snapshots/snapshots/dt=2026-10-27/positions.ndjson.gz")
        .contains("manifest sha256 aaaaaaaaaaaa…")
        .contains("INTACT    5 manifests chained; this one links to 2026-10-26's (bbbbbbbbbbbb…)")
        .doesNotContain("passes for this date");
  }

  @Test
  @DisplayName("says how many passes it took when an earlier one did not tie")
  void reportsRetries() {
    String out =
        String.join("\n", CloseReport.fromRow(row("t", 3, 2)).render("eod-snapshots", CHAIN));

    assertThat(out).contains("3 passes for this date; 2 did not tie");
  }

  @Test
  @DisplayName("an unannounced sign-off is reported as such, not as published")
  void unannounced() {
    String out =
        String.join("\n", CloseReport.fromRow(row("f", 1, 0)).render("eod-snapshots", CHAIN));

    assertThat(out).contains("NOT YET published");
  }

  @Test
  void refusesARowOfTheWrongShape() {
    assertThatThrownBy(() -> CloseReport.fromRow(List.of("2026-10-27")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void queriesAreParameterisedOnlyByTheDate() {
    assertThat(CloseReport.QUERY.formatted("2026-10-27")).contains("DATE '2026-10-27'");
    assertThat(HashChainCheck.QUERY.formatted("2026-10-27")).contains("DATE '2026-10-27'");
    assertThat(CloseProgress.QUERY.formatted("2026-10-27")).contains("DATE '2026-10-27'");
  }
}
