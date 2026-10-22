package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CustodianReconciliationPropertiesTest {

  @Test
  void defaultsFillInWhenNothingIsConfigured() {
    CustodianReconciliationProperties properties =
        new CustodianReconciliationProperties(null, 0, 0);

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
    assertThat(properties.realBreakEscalationDays()).isEqualTo(3);
    assertThat(properties.timingDifferenceEscalationDays()).isEqualTo(5);
  }

  @Test
  void configuredValuesAreKept() {
    CustodianReconciliationProperties properties =
        new CustodianReconciliationProperties(Duration.ofMinutes(30), 1, 2);

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofMinutes(30));
    assertThat(properties.realBreakEscalationDays()).isEqualTo(1);
    assertThat(properties.timingDifferenceEscalationDays()).isEqualTo(2);
  }
}
