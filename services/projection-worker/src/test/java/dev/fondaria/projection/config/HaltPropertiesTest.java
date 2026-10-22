package dev.fondaria.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HaltPropertiesTest {

  @Test
  void defaultsMatchAdr0032() {
    HaltProperties properties = new HaltProperties(null, null, null);

    assertThat(properties.projectionName()).isEqualTo("projection-worker");
    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(1));
    assertThat(properties.queryTimeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aBlankProjectionNameFallsBackToTheDefault() {
    assertThat(new HaltProperties("  ", null, null).projectionName())
        .isEqualTo("projection-worker");
  }

  @Test
  void theQueryTimeoutRoundsUpToWholeSeconds() {
    assertThat(new HaltProperties(null, null, Duration.ofMillis(1)).queryTimeoutSeconds())
        .isEqualTo(1);
    assertThat(new HaltProperties(null, null, Duration.ofMillis(1500)).queryTimeoutSeconds())
        .isEqualTo(2);
  }

  @Test
  void aNonPositivePollIntervalFailsStartupNamingTheVariable() {
    assertThatThrownBy(() -> new HaltProperties(null, Duration.ZERO, null))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_HALT_POLL_INTERVAL");
  }

  @Test
  void aNonPositiveQueryTimeoutFailsStartup() {
    assertThatThrownBy(() -> new HaltProperties(null, null, Duration.ofSeconds(-1)))
        .isInstanceOf(ConfigurationException.class);
  }
}
