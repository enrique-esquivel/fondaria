package dev.fondaria.bridge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BridgePropertiesTest {

  @Test
  void absentBatchSettingsDefault() {
    BridgeProperties properties = new BridgeProperties("rabbitmq", 0, null, null, 0, null);

    assertThat(properties.prefetch()).isEqualTo(64);
    assertThat(properties.batchSize()).isEqualTo(32);
    assertThat(properties.batchWait()).isEqualTo(Duration.ofMillis(10));
  }

  @Test
  void aBatchLargerThanThePrefetchFailsStartup() {
    assertThatThrownBy(() -> new BridgeProperties("rabbitmq", 64, null, null, 65, null))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("exceeds BRIDGE_AMQP_PREFETCH");
  }

  @Test
  void aZeroBatchWaitFailsStartup() {
    assertThatThrownBy(() -> new BridgeProperties("rabbitmq", 64, null, null, 32, Duration.ZERO))
        .isInstanceOf(ConfigurationException.class);
  }
}
