package dev.fondaria.eodjob.signoff;

import dev.fondaria.eodjob.config.SignoffProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * The RabbitMQ implementation of {@link CloseSignedOffPublisher} (ADR-0022).
 *
 * <p>Publishes to the {@code close.signed-off.v1} fanout exchange with publisher confirms enabled.
 * {@link SignoffService} sets {@code workflow.close_signoff.announced_at} only after this returns,
 * so a failure here leaves the sign-off recorded but not announced and the next sweep re-publishes.
 */
@Component
public class AmqpCloseSignedOffPublisher implements CloseSignedOffPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(AmqpCloseSignedOffPublisher.class);

  private final RabbitTemplate rabbitTemplate;
  private final String exchangeName;
  private final Duration confirmTimeout;
  private final RabbitMetrics metrics;

  public AmqpCloseSignedOffPublisher(
      RabbitTemplate rabbitTemplate, SignoffProperties properties, RabbitMetrics metrics) {
    this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
    this.exchangeName = properties.exchangeName();
    this.confirmTimeout = properties.publishConfirmTimeout();
    this.metrics = metrics;
  }

  @Override
  public void publish(CloseSignedOffEvent event) {
    long start = System.nanoTime();
    try {
      CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
      rabbitTemplate.convertAndSend(exchangeName, event.key(), event.toJson(), correlationData);
      CorrelationData.Confirm confirm =
          correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (confirm == null || !confirm.isAck()) {
        String reason = confirm != null ? confirm.getReason() : "no confirm returned";
        metrics.publishCompleted(exchangeName, System.nanoTime() - start, true);
        throw new PublishFailedException("RabbitMQ nacked close.signed-off.v1: " + reason);
      }
      metrics.publishCompleted(exchangeName, System.nanoTime() - start, false);
      LOGGER.info(
          "close.signed-off.v1 published: businessDate={} lastBusinessDayOfMonth={} positions={}",
          event.businessDate(),
          event.lastBusinessDayOfMonth(),
          event.snapshotPositionCount());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.publishCompleted(exchangeName, System.nanoTime() - start, true);
      throw new PublishFailedException("interrupted while awaiting RabbitMQ confirmation", e);
    } catch (ExecutionException | TimeoutException e) {
      metrics.publishCompleted(exchangeName, System.nanoTime() - start, true);
      throw new PublishFailedException(
          "RabbitMQ did not confirm close.signed-off.v1 within " + confirmTimeout, e);
    }
  }
}
