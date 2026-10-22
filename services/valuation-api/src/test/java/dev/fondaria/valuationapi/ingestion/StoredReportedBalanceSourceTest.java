package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.valuationapi.support.InMemoryAcceptedValuationRepository;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seam Task 20.2 left for this task, and what it must refuse to invent. */
class StoredReportedBalanceSourceTest {

  private static final LocalDate THURSDAY = LocalDate.of(2026, 8, 20);

  @Test
  @DisplayName("an accepted valuation is handed back as MXN cents, unchanged")
  void returnsTheAcceptedReportedValue() {
    InMemoryAcceptedValuationRepository accepted = new InMemoryAcceptedValuationRepository();
    FundId fund = FundId.of(UUID.randomUUID());
    accepted.seed(fund, THURSDAY, 105_000_000L);

    Money reported = new StoredReportedBalanceSource(accepted).reportedValueFor(fund, THURSDAY);

    assertThat(reported).isEqualTo(Money.of(105_000_000L, CurrencyCode.MXN));
  }

  /**
   * The behaviour that matters most. A missing valuation must be an absence, never a neighbouring
   * date and never an interpolation — a price the platform invented is not a price anybody
   * reported, and it would sit in front of every participant in the fund.
   */
  @Test
  @DisplayName("a date with no accepted valuation has no reported balance, and none is invented")
  void refusesToInventAMissingValuation() {
    InMemoryAcceptedValuationRepository accepted = new InMemoryAcceptedValuationRepository();
    FundId fund = FundId.of(UUID.randomUUID());
    accepted.seed(fund, THURSDAY.minusDays(1), 105_000_000L);

    assertThatThrownBy(
            () -> new StoredReportedBalanceSource(accepted).reportedValueFor(fund, THURSDAY))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  @DisplayName("a fund with nothing accepted at all has no reported balance")
  void refusesAnUnknownFund() {
    assertThatThrownBy(
            () ->
                new StoredReportedBalanceSource(new InMemoryAcceptedValuationRepository())
                    .reportedValueFor(FundId.of(UUID.randomUUID()), THURSDAY))
        .isInstanceOf(NoSuchElementException.class);
  }
}
