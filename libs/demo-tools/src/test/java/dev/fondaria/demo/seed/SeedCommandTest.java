package dev.fondaria.demo.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.demo.Args;
import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.manifest.FundRecord;
import dev.fondaria.demo.manifest.Manifest;
import dev.fondaria.demo.manifest.OrganizationRecord;
import dev.fondaria.demo.manifest.SeedBatch;
import dev.fondaria.demo.valuation.NavSeriesLog;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeedCommandTest {

  private static final LocalDate LAUNCH = LocalDate.of(2026, 10, 26);

  @TempDir Path tempDir;

  @Test
  void organizationCountDefaultsToAll() {
    assertThat(SeedCommand.organizationCount(Args.parse(new String[] {"seed"}, 1), 12))
        .isEqualTo(12);
  }

  @Test
  void organizationCountAcceptsASubset() {
    Args args = Args.parse(new String[] {"seed", "--organizations", "8"}, 1);
    assertThat(SeedCommand.organizationCount(args, 12)).isEqualTo(8);
  }

  @Test
  void organizationCountRejectsOutOfRange() {
    for (String value : new String[] {"0", "13", "-1"}) {
      Args args = Args.parse(new String[] {"seed", "--organizations", value}, 1);
      assertThatThrownBy(() -> SeedCommand.organizationCount(args, 12))
          .isInstanceOf(DemoToolsException.class);
    }
  }

  @Test
  void firstEightOrganizationsAreTheSmallestSixtyThousand() {
    int[] sizes = OrganizationSizes.generate();
    long firstEight = 0;
    for (int i = 0; i < 8; i++) {
      firstEight += sizes[i];
    }
    assertThat(firstEight).isEqualTo(60_000);
  }

  @Test
  void settleTimeoutGrowsWithTheBatchSoTheLargestSeedOrganizationIsNotCutOff() {
    assertThat(SeedCommand.settleTimeout(191)).isLessThan(Duration.ofMinutes(4));
    // 31,115 rows took over four minutes to stage on the small GKE profile.
    assertThat(SeedCommand.settleTimeout(31_115)).isGreaterThan(Duration.ofMinutes(8));
  }

  @Test
  void everyFundOpensAtItsLaunchDateWithNothingReported() {
    FundRecord one = fund("fund-1", LAUNCH);
    FundRecord two = fund("fund-2", LAUNCH);
    FundRecord later = fund("fund-3", LAUNCH.plusDays(3));

    assertThat(SeedCommand.openingValuations(List.of(one, two, later)))
        .containsExactly(
            Map.entry(LAUNCH, Map.of("fund-1", 0L, "fund-2", 0L)),
            Map.entry(LAUNCH.plusDays(3), Map.of("fund-3", 0L)));
  }

  @Test
  void baselineCountsOnlyCashDealtAtEachFundsOpeningAcrossEveryManifestOrganization() {
    Manifest manifest = Manifest.empty();
    manifest.funds().add(fund("fund-1", LAUNCH));
    manifest.funds().add(fund("fund-2", LAUNCH));
    manifest.funds().add(fund("fund-3", LAUNCH));
    manifest.organizations().add(organization("fund-1", Optional.of(LAUNCH), 1_000L));
    manifest.organizations().add(organization("fund-1", Optional.of(LAUNCH), 2_500L));
    // A dealing lag: a later flow, not opening cash.
    manifest.organizations().add(organization("fund-1", Optional.of(LAUNCH.plusDays(1)), 9_999L));
    // Rejected: no cash dealt at all.
    manifest.organizations().add(organization("fund-2", Optional.empty(), 7_777L));
    manifest.organizations().add(organization("fund-3", Optional.of(LAUNCH), 400L));

    assertThat(SeedCommand.openingBaseline(manifest))
        .containsExactly(Map.entry("fund-1", 3_500L), Map.entry("fund-3", 400L));
  }

  @Test
  void baselineIsAppendedOnceAndSupersededOnlyWhileTheLogStillEndsAtLaunch() {
    NavSeriesLog log = NavSeriesLog.readFrom(tempDir.resolve("nav-series.json"));
    List<FundRecord> funds = List.of(fund("fund-1", LAUNCH), fund("fund-2", LAUNCH));

    SeedCommand.recordOpeningBaseline(log, funds, Map.of("fund-1", 1_000L, "fund-2", 50L));
    SeedCommand.recordOpeningBaseline(log, funds, Map.of("fund-1", 1_000L, "fund-2", 50L));
    assertThat(log.lastEntry("fund-1"))
        .contains(new NavSeriesLog.Entry(LAUNCH.toString(), "fund-1", null, 1_000L, 0));

    // A resumed run added launch-day cash: the new total supersedes the earlier baseline.
    SeedCommand.recordOpeningBaseline(log, funds, Map.of("fund-1", 1_800L, "fund-2", 50L));
    assertThat(log.lastReportedValueCents("fund-1")).contains(1_800L);

    // Valued since (a falling day): the log has moved on and is no longer seed's to restate.
    log.append(new NavSeriesLog.Entry(LAUNCH.plusDays(1).toString(), "fund-2", -300L, 48L, 0));
    SeedCommand.recordOpeningBaseline(log, funds, Map.of("fund-1", 1_800L, "fund-2", 90L));
    assertThat(log.lastReportedValueCents("fund-2")).contains(48L);

    Path file = tempDir.resolve("nav-series.json");
    log.writeTo(file);
    assertThat(NavSeriesLog.readFrom(file).lastEntry("fund-1").orElseThrow().date())
        .isEqualTo(LAUNCH.toString());
  }

  @Test
  void aManifestWithoutRecordedSeedBatchesIsRefusedBeforeAnythingIsSent() {
    Manifest manifest = Manifest.empty();
    manifest.organizations().add(organization("fund-1", Optional.of(LAUNCH), 1_000L));
    SeedCommand.requireSeedBatchesRecorded(manifest);

    manifest
        .organizations()
        .add(
            new OrganizationRecord(
                "org-legacy", "Legacy", "PLAN-0", "fund-1", 10, List.of(), Optional.empty()));
    assertThatThrownBy(() -> SeedCommand.requireSeedBatchesRecorded(manifest))
        .isInstanceOf(DemoToolsException.class)
        .hasMessageContaining("org-legacy");
  }

  private static FundRecord fund(String fundId, LocalDate launchDate) {
    return new FundRecord(fundId, 1L, "CAJA_DE_AHORRO", fundId, launchDate);
  }

  private static OrganizationRecord organization(
      String fundId, Optional<LocalDate> dealingDate, long declaredTotalCents) {
    return new OrganizationRecord(
        "org-" + declaredTotalCents,
        "Org",
        "PLAN",
        fundId,
        10,
        List.of(),
        Optional.of(new SeedBatch("batch-" + declaredTotalCents, dealingDate, declaredTotalCents)));
  }
}
