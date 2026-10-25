package dev.fondaria.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActivityPropertiesTest {

  @Test
  void defaultsMatchAdr0035() {
    ActivityProperties properties = new ActivityProperties(null, null);

    assertThat(properties.logInterval()).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.depthTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aNonPositiveLogIntervalFailsStartupNamingTheVariable() {
    assertThatThrownBy(() -> new ActivityProperties(Duration.ZERO, null))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_ACTIVITY_LOG_INTERVAL");
  }

  @Test
  void aNonPositiveDepthTimeoutFailsStartup() {
    assertThatThrownBy(() -> new ActivityProperties(null, Duration.ofSeconds(-1)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("depth timeout");
  }
}
