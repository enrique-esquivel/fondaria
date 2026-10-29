package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProjectionCatchUpPropertiesTest {

  @Test
  @DisplayName("absent settings bind to working defaults")
  void absentSettingsDefault() {
    ProjectionCatchUpProperties properties = new ProjectionCatchUpProperties(null, null);

    assertThat(properties.quietPeriod()).isEqualTo(Duration.ofSeconds(15));
    assertThat(properties.stallAfter()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  @DisplayName("a stall shorter than the quiet period would run every pass on a drained consumer")
  void stallMustExceedQuiet() {
    assertThatThrownBy(
            () -> new ProjectionCatchUpProperties(Duration.ofMinutes(1), Duration.ofSeconds(30)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("must exceed");
  }

  @Test
  @DisplayName("a zero quiet period is refused")
  void quietMustBePositive() {
    assertThatThrownBy(() -> new ProjectionCatchUpProperties(Duration.ZERO, Duration.ofMinutes(2)))
        .isInstanceOf(ConfigurationException.class);
  }
}
