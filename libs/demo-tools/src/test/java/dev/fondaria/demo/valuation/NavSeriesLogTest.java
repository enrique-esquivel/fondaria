package dev.fondaria.demo.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavSeriesLogTest {

  @TempDir Path tempDir;

  @Test
  void emptyLogHasNoPreviousValueForAnyFund() {
    NavSeriesLog log = NavSeriesLog.readFrom(tempDir.resolve("nav-series.json"));
    assertThat(log.lastReportedValueCents("fund-1")).isEmpty();
  }

  @Test
  void lastReportedValueIsTheMostRecentEntryForThatFund() {
    NavSeriesLog log = NavSeriesLog.readFrom(tempDir.resolve("nav-series.json"));
    log.append(new NavSeriesLog.Entry("2026-10-01", "fund-1", null, 1_000L, 0));
    log.append(new NavSeriesLog.Entry("2026-10-02", "fund-1", 300L, 1_003L, 0));
    log.append(new NavSeriesLog.Entry("2026-10-02", "fund-2", null, 2_000L, 0));

    assertThat(log.lastReportedValueCents("fund-1")).contains(1_003L);
    assertThat(log.lastReportedValueCents("fund-2")).contains(2_000L);
  }

  @Test
  void survivesARoundTripThroughDisk() {
    Path file = tempDir.resolve("nav-series.json");
    NavSeriesLog log = NavSeriesLog.readFrom(file);
    log.append(new NavSeriesLog.Entry("2026-10-01", "fund-1", null, 1_000L, 0));
    log.writeTo(file);

    NavSeriesLog reloaded = NavSeriesLog.readFrom(file);
    assertThat(reloaded.lastReportedValueCents("fund-1")).contains(1_000L);
  }

  @Test
  void missingFileYieldsAnEmptyLog() {
    Optional<Long> value =
        NavSeriesLog.readFrom(tempDir.resolve("does-not-exist.json"))
            .lastReportedValueCents("fund-1");
    assertThat(value).isEmpty();
  }
}
