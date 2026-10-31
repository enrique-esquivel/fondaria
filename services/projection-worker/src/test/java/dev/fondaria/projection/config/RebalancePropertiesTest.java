package dev.fondaria.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RebalancePropertiesTest {

  @Test
  void defaultsMatchAdr0042() {
    RebalanceProperties properties = new RebalanceProperties(null);

    assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.enabled()).isTrue();
  }

  @Test
  void aZeroIntervalDisablesRebalancing() {
    assertThat(new RebalanceProperties(Duration.ZERO).enabled()).isFalse();
  }

  @Test
  void aNegativeIntervalFailsStartupNamingTheVariable() {
    assertThatThrownBy(() -> new RebalanceProperties(Duration.ofSeconds(-1)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_REBALANCE_INTERVAL");
  }
}
