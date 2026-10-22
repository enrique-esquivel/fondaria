package dev.fondaria.demo.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FundFlowsTest {

  @TempDir Path tempDir;

  private static final LocalDate DAY_1 = LocalDate.of(2026, 10, 6);
  private static final LocalDate DAY_2 = LocalDate.of(2026, 10, 7);

  private static final LocalDate DAY_3 = LocalDate.of(2026, 10, 8);

  @Test
  void accumulatesAcrossMultipleAddsForTheSameFundAndDate() {
    FundFlows flows = FundFlows.readFrom(tempDir.resolve("fund-flows.json"));
    flows.add("fund-1", DAY_1, 1_000L);
    flows.add("fund-1", DAY_1, 2_000L);
    flows.add("fund-2", DAY_1, 500L);

    assertThat(flows.drainDealtBefore("fund-1", DAY_2)).isEqualTo(3_000L);
    assertThat(flows.drainDealtBefore("fund-2", DAY_2)).isEqualTo(500L);
  }

  @Test
  void contributionsDealingOnTheValuationDateStayPending() {
    FundFlows flows = FundFlows.readFrom(tempDir.resolve("fund-flows.json"));
    flows.add("fund-1", DAY_1, 1_000L);
    flows.add("fund-1", DAY_2, 5_000L);

    assertThat(flows.drainDealtBefore("fund-1", DAY_2)).isEqualTo(1_000L);
    assertThat(flows.drainDealtBefore("fund-1", DAY_2)).isZero();
    assertThat(flows.drainDealtBefore("fund-1", DAY_3)).isEqualTo(5_000L);
  }

  @Test
  void drainingResetsToZero() {
    FundFlows flows = FundFlows.readFrom(tempDir.resolve("fund-flows.json"));
    flows.add("fund-1", DAY_1, 1_000L);
    flows.drainDealtBefore("fund-1", DAY_2);
    assertThat(flows.drainDealtBefore("fund-1", DAY_2)).isZero();
  }

  @Test
  void drainingAnUnknownFundOrALaterDateReturnsZero() {
    FundFlows flows = FundFlows.readFrom(tempDir.resolve("fund-flows.json"));
    assertThat(flows.drainDealtBefore("never-added", DAY_2)).isZero();
    flows.add("fund-1", DAY_2, 1_000L);
    assertThat(flows.drainDealtBefore("fund-1", DAY_1)).isZero();
  }

  @Test
  void survivesARoundTripThroughDisk() {
    Path file = tempDir.resolve("fund-flows.json");
    FundFlows flows = FundFlows.readFrom(file);
    flows.add("fund-1", DAY_1, 12_345L);
    flows.writeTo(file);

    FundFlows reloaded = FundFlows.readFrom(file);
    assertThat(reloaded.drainDealtBefore("fund-1", DAY_2)).isEqualTo(12_345L);
  }
}
