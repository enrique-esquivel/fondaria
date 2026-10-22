package dev.fondaria.projection.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckpointPropertiesTest {

  @Test
  void defaultsMatchAdr0032() {
    CheckpointProperties properties = new CheckpointProperties("pod-1", null, null);

    assertThat(properties.instanceId()).isEqualTo("pod-1");
    assertThat(properties.activeWindow()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.retention()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void aBlankInstanceIdFallsBackToAUniqueOne() {
    CheckpointProperties first = new CheckpointProperties(" ", null, null);
    CheckpointProperties second = new CheckpointProperties(null, null, null);

    assertThat(first.instanceId()).startsWith("instance-");
    assertThat(first.instanceId()).isNotEqualTo(second.instanceId());
  }

  @Test
  void retentionMustExceedTheActiveWindow() {
    assertThatThrownBy(
            () -> new CheckpointProperties("pod", Duration.ofMinutes(5), Duration.ofMinutes(5)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_CHECKPOINT_RETENTION");
  }

  @Test
  void theActiveWindowMustExceedTheRetryBackoffCeiling() {
    CheckpointProperties properties =
        new CheckpointProperties("pod", Duration.ofSeconds(30), Duration.ofHours(1));

    assertThatThrownBy(() -> properties.requireActiveWindowExceeds(Duration.ofSeconds(30)))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_RETRY_MAX_BACKOFF");
    properties.requireActiveWindowExceeds(Duration.ofSeconds(29));
  }

  @Test
  void theActiveWindowMustBePositive() {
    assertThatThrownBy(() -> new CheckpointProperties("pod", Duration.ZERO, null))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("PROJECTION_CHECKPOINT_ACTIVE_WINDOW");
  }
}
