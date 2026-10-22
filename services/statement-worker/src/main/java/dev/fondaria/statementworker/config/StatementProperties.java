package dev.fondaria.statementworker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task 7.5b's own operational tuning, bound from the environment (ADR-0022). Every value has a
 * working default (AGENTS.md §4: production fails to start on an absent secret, but tuning is not
 * one) — the RabbitMQ host itself is bound the same way {@code eod-job}'s {@code SignoffProperties}
 * mirrors it, required and named on absence.
 *
 * @param amqpHost {@code STATEMENT_AMQP_HOST}. Required — the same RabbitMQ broker every other
 *     service on the platform uses.
 * @param workerConcurrency how many consumer channels drain {@code statement.jobs.v1} concurrently.
 * @param publishConfirmTimeout how long the scheduler waits for RabbitMQ to confirm the whole
 *     month's job messages before treating the publish as failed and leaving {@code
 *     close.signed-off.v1} unacknowledged for redelivery.
 *     <p>The settlement sweep's own interval ({@code fondaria.statement.settlement-sweep-interval})
 *     is read directly by {@code StatementRunSettler}'s {@code @Scheduled} annotation via SpEL, not
 *     through this record — nothing else needs it, and it is not a startup-validated value.
 */
@ConfigurationProperties(prefix = "fondaria.statement")
public record StatementProperties(
    String amqpHost, int workerConcurrency, Duration publishConfirmTimeout) {

  public static final String AMQP_HOST_VARIABLE = "STATEMENT_AMQP_HOST";

  static final String REMEDY =
      "Set "
          + AMQP_HOST_VARIABLE
          + " to the RabbitMQ host (the same broker every other service uses). See"
          + " infrastructure/compose/.env.example.";

  public StatementProperties {
    workerConcurrency = workerConcurrency <= 0 ? 6 : workerConcurrency;
    publishConfirmTimeout =
        publishConfirmTimeout == null ? Duration.ofSeconds(60) : publishConfirmTimeout;
  }

  /** Fails startup naming the absent variable. Never echoes a value (AGENTS.md §7). */
  public void requireComplete() {
    if (amqpHost == null || amqpHost.isBlank()) {
      throw new ConfigurationException(
          "Required environment variable " + AMQP_HOST_VARIABLE + " is not set", REMEDY);
    }
  }
}
