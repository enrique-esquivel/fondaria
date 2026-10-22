package dev.fondaria.demo.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValuationFileGeneratorTest {

  private static final LocalDate DATE = LocalDate.of(2026, 10, 6);

  @Test
  void headerMatchesValuationFileV1() {
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(
            DATE,
            0L,
            List.of(new ValuationFileGenerator.FundValuationInput("F1", 1_000_00L, 0L)),
            "DEMO");
    assertThat(file.csv().lines().findFirst()).contains(ValuationFileGenerator.COLUMN_HEADER);
  }

  @Test
  void positiveMovesIncreaseTheReportedValue() {
    // +0.3% of 1,000,000 cents = 3,000 cents.
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(
            DATE,
            300L,
            List.of(new ValuationFileGenerator.FundValuationInput("F1", 1_000_000L, 0L)),
            "DEMO");
    assertThat(file.reportedValueCentsByFund().get("F1")).isEqualTo(1_003_000L);
  }

  @Test
  void negativeMovesDecreaseTheReportedValue() {
    // -1.5% of 1,000,000 cents = -15,000 cents.
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(
            DATE,
            -1500L,
            List.of(new ValuationFileGenerator.FundValuationInput("F1", 1_000_000L, 0L)),
            "DEMO");
    assertThat(file.reportedValueCentsByFund().get("F1")).isEqualTo(985_000L);
  }

  @Test
  void allottedContributionsAreAddedOnTopOfTheMove() {
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(
            DATE,
            0L,
            List.of(new ValuationFileGenerator.FundValuationInput("F1", 1_000_000L, 50_000L)),
            "DEMO");
    assertThat(file.reportedValueCentsByFund().get("F1")).isEqualTo(1_050_000L);
  }

  /**
   * Row 26s: the NAV is reported value / units in issue. Contributions dealt today are not in
   * today's value, so with a contribution larger than the pool the implied NAV still moves by the
   * market move only, on a rising, a falling and a flat day.
   */
  @Test
  void aContributionLargerThanThePoolNeverMovesTheImpliedNav() {
    long pool = 1_000_000L;
    for (long move : new long[] {300L, -1500L, 0L}) {
      // Nothing allotted yet: the contribution (5x the pool) is pending, so it is not passed in.
      ValuationFileGenerator.GeneratedFile file =
          ValuationFileGenerator.generate(
              DATE,
              move,
              List.of(new ValuationFileGenerator.FundValuationInput("F1", pool, 0L)),
              "DEMO");
      long expected = pool + pool * move / NavSeries.MOVE_SCALE;
      assertThat(file.reportedValueCentsByFund().get("F1")).isEqualTo(expected);
    }
  }

  @Test
  void oneRowPerFund() {
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generate(
            DATE,
            100L,
            List.of(
                new ValuationFileGenerator.FundValuationInput("F1", 1_000_000L, 0L),
                new ValuationFileGenerator.FundValuationInput("F2", 2_000_000L, 0L)),
            "DEMO");
    assertThat(file.rowCount()).isEqualTo(2);
    assertThat(file.csv().lines().count()).isEqualTo(3); // header + 2 rows
  }

  @Test
  void initialValuationReportsTheGivenValueUnchanged() {
    ValuationFileGenerator.GeneratedFile file =
        ValuationFileGenerator.generateInitial(DATE, Map.of("F1", 1_000L), "DEMO-SEED");
    assertThat(file.reportedValueCentsByFund()).containsEntry("F1", 1_000L);
    assertThat(file.csv()).contains("F1,2026-10-06,1000,DEMO-SEED");
  }

  @Test
  void navSeriesIncludesAtLeastOneFall() {
    assertThat(NavSeries.SCRIPTED_MOVES).anyMatch(move -> move < 0);
  }

  @Test
  void navSeriesCyclesRatherThanIndexingOutOfBounds() {
    int size = NavSeries.SCRIPTED_MOVES.size();
    assertThat(NavSeries.moveAt(size)).isEqualTo(NavSeries.moveAt(0));
    assertThat(NavSeries.moveAt(size + 1)).isEqualTo(NavSeries.moveAt(1));
  }
}
