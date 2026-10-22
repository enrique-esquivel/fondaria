package dev.fondaria.statementworker.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.fondaria.statementworker.config.AmqpTopologyConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The scheduler's one input: {@code close.signed-off.v1} (Task 7.5a, ADR-0022), consumed here as a
 * <b>single active consumer</b> — queue {@code statement.close-signoff.v1} bound to fanout exchange
 * {@code close.signed-off.v1}, {@code concurrency = 1} ({@code AmqpConsumeConfiguration}).
 *
 * <p><b>Acknowledgement is manual and last</b>, the same discipline {@code LedgerEventListener}
 * uses: the message is acknowledged only once {@link StatementScheduler#handle} returns without
 * throwing, so a crash mid-fan-out leaves the message unacknowledged and the event is redelivered —
 * safe, because {@link StatementScheduler} always reprocesses rather than skipping on the strength
 * of a database row.
 *
 * <p>An unsupported {@code schemaVersion} or a message missing a required field is routed the same
 * way {@code LedgerEventListener} routes a decode failure: never a best-effort parse.
 */
@Component
public class CloseSignedOffListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(CloseSignedOffListener.class);

  private final ObjectMapper objectMapper;
  private final StatementScheduler scheduler;

  public CloseSignedOffListener(ObjectMapper objectMapper, StatementScheduler scheduler) {
    this.objectMapper = objectMapper;
    this.scheduler = scheduler;
  }

  @RabbitListener(
      queues = AmqpTopologyConfiguration.CLOSE_SIGNOFF_QUEUE,
      containerFactory = "closeSignoffListenerContainerFactory")
  public void onMessage(Message message, Channel channel) throws Exception {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    try {
      dispatchWithRetry(message);
      channel.basicAck(deliveryTag, false);
      LOGGER.debug("close.signed-off.v1 processed");
    } catch (MalformedCloseSignedOffEventException | StatementFanOutException permanent) {
      LOGGER.error(
          "permanent failure processing close.signed-off.v1; message rejected: {}",
          permanent.getMessage(),
          permanent);
      channel.basicReject(deliveryTag, false);
      throw permanent;
    }
  }

  private void dispatchWithRetry(Message message) {
    Duration backoff = Duration.ofSeconds(1);
    Duration maxBackoff = Duration.ofSeconds(30);
    while (!Thread.currentThread().isInterrupted()) {
      try {
        dispatch(message);
        return;
      } catch (MalformedCloseSignedOffEventException | StatementFanOutException permanent) {
        throw permanent;
      } catch (Exception transientEx) {
        LOGGER.warn(
            "transient failure processing close.signed-off.v1: {} - retrying in {}",
            transientEx.getMessage(),
            backoff);
        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("interrupted while retrying transient failure", ie);
        }
        long next = Math.min(backoff.toMillis() * 2, maxBackoff.toMillis());
        backoff = Duration.ofMillis(next);
      }
    }
  }

  private void dispatch(Message message) {
    String body = new String(message.getBody(), StandardCharsets.UTF_8);
    CloseSignedOffEvent event = CloseSignedOffEvent.fromJson(objectMapper, body);
    if (!event.isSupportedVersion()) {
      throw new MalformedCloseSignedOffEventException(
          "close.signed-off.v1 message carries unsupported schemaVersion " + event.schemaVersion());
    }
    scheduler.handle(event);
  }
}
