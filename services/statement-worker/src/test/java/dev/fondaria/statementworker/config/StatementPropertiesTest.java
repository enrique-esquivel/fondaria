package dev.fondaria.statementworker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The statement worker's tuning, and the one value whose absence must stop startup (ADR-0022). */
class StatementPropertiesTest {

  @Test
  @DisplayName("absent tuning values fall back to working defaults")
  void absentValuesHaveWorkingDefaults() {
    StatementProperties properties = new StatementProperties("rabbitmq", 0, null);

    assertThat(properties.workerConcurrency()).isEqualTo(6);
    assertThat(properties.publishConfirmTimeout()).isEqualTo(Duration.ofSeconds(60));
    assertThatCode(properties::requireComplete).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("an absent broker variable fails startup naming it")
  void anAbsentBrokerFailsStartup() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () -> new StatementProperties("  ", 6, Duration.ofSeconds(60)).requireComplete())
        .withMessageContaining(StatementProperties.AMQP_HOST_VARIABLE);

    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () -> new StatementProperties(null, 6, Duration.ofSeconds(60)).requireComplete())
        .withMessageContaining(StatementProperties.AMQP_HOST_VARIABLE);
  }
}
