package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The document that travels with a day's file, and the chain it belongs to. */
class SnapshotManifestTest {

  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);

  private final FundId fundId = new FundId(UUID.randomUUID());

  @Test
  @DisplayName(
      "carries what the phase document asks of it, and the number the tie was made against")
  void carriesTheRequiredFields() {
    SnapshotManifest manifest = manifest(total(100_00000000L, 100_00000000L), Optional.empty());

    String json = new String(manifest.render().bytes(), StandardCharsets.UTF_8);

    assertThat(json)
        .contains("\"schemaVersion\":\"v1\"")
        .contains("\"businessDate\":\"2026-03-02\"")
        .contains("\"positionCount\":2")
        .contains("\"positionsContentSha256\":\"" + "a".repeat(64) + "\"")
        .contains("\"previousManifestSha256\":null")
        .contains("\"unitsScaled\":10000000000")
        .contains("\"valueMinorUnits\":10700")
        .contains("\"unitsInIssueScaled\":10000000000")
        .contains("\"ties\":true");
  }

  @Test
  @DisplayName("renders the same bytes twice — there is no clock in it")
  void isReproducible() {
    SnapshotManifest manifest = manifest(total(100_00000000L, 100_00000000L), Optional.empty());

    assertThat(manifest.render().sha256()).isEqualTo(manifest.render().sha256());
  }

  @Test
  @DisplayName("a different previous-day hash makes a different manifest")
  void theChainLinkIsPartOfTheDocument() {
    SnapshotManifest genesis = manifest(total(1L, 1L), Optional.empty());
    SnapshotManifest chained = manifest(total(1L, 1L), Optional.of("b".repeat(64)));

    assertThat(chained.render().sha256())
        .as("otherwise a day could be re-parented without its hash changing")
        .isNotEqualTo(genesis.render().sha256());
  }

  @Test
  @DisplayName("reports the funds that did not tie, and the signed drift on each")
  void reportsUntiedFunds() {
    SnapshotManifest manifest = manifest(total(90_00000000L, 100_00000000L), Optional.empty());

    assertThat(manifest.ties()).isFalse();
    assertThat(manifest.untiedFunds()).hasSize(1);
    assertThat(manifest.untiedFunds().get(0).driftScaled()).isEqualTo(-10_00000000L);
    assertThat(new String(manifest.render().bytes(), StandardCharsets.UTF_8))
        .contains("\"ties\":false");
  }

  private SnapshotFundTotal total(long unitsScaled, long unitsInIssueScaled) {
    return new SnapshotFundTotal(
        fundId,
        1001L,
        2L,
        Units.of(unitsScaled),
        Money.of(10_700L, CurrencyCode.MXN),
        Units.of(unitsInIssueScaled));
  }

  private SnapshotManifest manifest(
      SnapshotFundTotal fundTotal, Optional<String> previousManifestSha256) {
    return new SnapshotManifest(
        DATE,
        2L,
        "snapshots/dt=2026-03-02/positions.ndjson.gz",
        "a".repeat(64),
        List.of(fundTotal),
        previousManifestSha256);
  }
}
