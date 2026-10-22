package dev.fondaria.demo.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.demo.DemoToolsException;
import dev.fondaria.demo.manifest.FundRecord;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValuationsCommandTest {

  private static final LocalDate LAUNCH = LocalDate.of(2026, 10, 2);

  @TempDir Path tempDir;

  private final FundRecord cajaUno =
      new FundRecord("fund-caja-uno", 1L, "CAJA_DE_AHORRO", "Caja de Ahorro Uno", LAUNCH);
  private final FundRecord retirement =
      new FundRecord("fund-retiro", 2L, "RETIREMENT_FUND", "Fondo de Retiro Patronal", LAUNCH);

  /**
   * Row 26p: under {@code make demo-seed ORGS=7} no employer invests in the retirement fund, so it
   * was never valued, and {@code valuations} refused to run at all.
   */
  @Test
  void aFundNeverValuedIsSkippedAndTheOthersAreStillValued() {
    NavSeriesLog log = NavSeriesLog.readFrom(tempDir.resolve("nav-series.json"));
    log.append(new NavSeriesLog.Entry("2026-10-02", "fund-caja-uno", null, 325_586_919L, 0));

    assertThat(ValuationsCommand.fundsToValue(List.of(cajaUno, retirement), log))
        .containsExactly(cajaUno);
  }

  @Test
  void noFundValuedAtAllIsStillAnError() {
    NavSeriesLog log = NavSeriesLog.readFrom(tempDir.resolve("nav-series.json"));

    assertThatThrownBy(() -> ValuationsCommand.fundsToValue(List.of(cajaUno, retirement), log))
        .isInstanceOf(DemoToolsException.class)
        .hasMessageContaining("seed");
  }
}
