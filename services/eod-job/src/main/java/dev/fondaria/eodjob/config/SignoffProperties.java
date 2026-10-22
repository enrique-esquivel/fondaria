package dev.fondaria.eodjob.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the sign-off pass (Task 7.5a, ADR-0022).
 *
 * <p>The RabbitMQ <em>connection</em> is bound by Spring under {@code spring.rabbitmq.*}; this
 * record mirrors the one value that must be present — the AMQP host — so an absent variable becomes
 * a {@link ConfigurationException} naming it rather than a publisher that silently connects to
 * {@code localhost} and never confirms a record.
 *
 * @param pollInterval how often the scheduled sign-off pass runs.
 * @param amqpHost {@code SIGNOFF_AMQP_HOST}. Required — the RabbitMQ host.
 * @param exchangeName exchange name for {@code close.signed-off.v1}. Defaults to {@code
 *     close.signed-off.v1}.
 * @param publishConfirmTimeout how long to wait for RabbitMQ to confirm {@code close.signed-off.v1}
 *     before giving up and letting the next sweep retry.
 */
@ConfigurationProperties(prefix = "fondaria.signoff")
public record SignoffProperties(
    Duration pollInterval, String amqpHost, String exchangeName, Duration publishConfirmTimeout) {

  public static final String AMQP_HOST_VARIABLE = "SIGNOFF_AMQP_HOST";

  static final String REMEDY =
      "Set "
          + AMQP_HOST_VARIABLE
          + " to the RabbitMQ host (the same broker every other service uses). See"
          + " infrastructure/compose/.env.example.";

  public SignoffProperties {
    pollInterval = pollInterval == null ? Duration.ofHours(1) : pollInterval;
    exchangeName =
        (exchangeName == null || exchangeName.isBlank()) ? "close.signed-off.v1" : exchangeName;
    publishConfirmTimeout =
        publishConfirmTimeout == null ? Duration.ofSeconds(30) : publishConfirmTimeout;
  }

  /** Fails startup naming the absent variable. Never echoes a value (AGENTS.md §7). */
  public void requireComplete() {
    if (amqpHost == null || amqpHost.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + AMQP_HOST_VARIABLE + " is not set", REMEDY);
    }
  }
}
