package dev.fondaria.statementworker.contribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContributionTotalsTest {

  @Test
  @DisplayName("each source accumulates independently")
  void sourcesAccumulateIndependently() {
    ContributionTotals totals =
        ContributionTotals.zero(CurrencyCode.MXN)
            .plus("EMPLOYEE", Money.of(1_000, CurrencyCode.MXN))
            .plus("EMPLOYEE", Money.of(500, CurrencyCode.MXN))
            .plus("EMPLOYER", Money.of(2_000, CurrencyCode.MXN))
            .plus("VOLUNTARY", Money.of(300, CurrencyCode.MXN));

    assertThat(totals.employee().cents()).isEqualTo(1_500L);
    assertThat(totals.employer().cents()).isEqualTo(2_000L);
    assertThat(totals.voluntary().cents()).isEqualTo(300L);
  }

  @Test
  @DisplayName("an unknown source is rejected rather than silently dropped")
  void unknownSourceIsRejected() {
    ContributionTotals zero = ContributionTotals.zero(CurrencyCode.MXN);
    assertThatThrownBy(() -> zero.plus("BONUS", Money.of(1, CurrencyCode.MXN)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
