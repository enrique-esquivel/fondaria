package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sign-off pass's tuning, and the one value whose absence must stop startup (ADR-0022). */
class SignoffPropertiesTest {

  @Test
  @DisplayName("absent tuning values fall back to a working cadence")
  void absentValuesHaveWorkingDefaults() {
    SignoffProperties properties = new SignoffProperties(null, "rabbitmq", null, null);

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
    assertThat(properties.exchangeName()).isEqualTo("close.signed-off.v1");
    assertThat(properties.publishConfirmTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThatCode(properties::requireComplete).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an absent broker variable fails startup naming it")
  void anAbsentBrokerFailsStartup() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () ->
                new SignoffProperties(
                        Duration.ofHours(1), "  ", "close.signed-off.v1", Duration.ofSeconds(30))
                    .requireComplete())
        .withMessageContaining(SignoffProperties.AMQP_HOST_VARIABLE);

    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(() -> new SignoffProperties(null, null, null, null).requireComplete())
        .withMessageContaining(SignoffProperties.AMQP_HOST_VARIABLE);
  }
}
