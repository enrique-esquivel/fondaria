package dev.fondaria.statementworker.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.fondaria.statementworker.schedule.MalformedStatementJobException;
import dev.fondaria.statementworker.schedule.StatementJob;
import dev.fondaria.statementworker.schedule.StatementJobsTopic;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The worker role's one input: {@code statement.jobs.v1} (Task 7.5b, ADR-0022). {@code concurrency
 * = fondaria.statement.worker-concurrency} ({@code AmqpConsumeConfiguration}) is the whole of
 * "using additional consumers rather than a faster single worker" — every consumer thread drains
 * the shared work queue and runs a fully independent render.
 *
 * <p><b>Acknowledgement is manual and last</b> — the same discipline {@code LedgerEventListener}
 * and {@link dev.fondaria.statementworker.schedule.CloseSignedOffListener} use. A crash between
 * writing the statement object and acknowledging leaves the message unacknowledged, so the job is
 * redelivered; {@link StatementRenderer#render} is idempotent (deterministic bytes, deterministic
 * key, an idempotent completion counter), so "only its unfinished job messages are redelivered"
 * (Task 7.5b's own acceptance criterion) holds without any special recovery path.
 */
@Component
public class StatementJobListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatementJobListener.class);

  private final ObjectMapper objectMapper;
  private final StatementRenderer renderer;
  private final DeadLetterPublisher deadLetters;
  private final RenderHalt halt;
  private final ListenerContainers containers;

  public StatementJobListener(
      ObjectMapper objectMapper,
      StatementRenderer renderer,
      DeadLetterPublisher deadLetters,
      RenderHalt halt,
      ListenerContainers containers) {
    this.objectMapper = objectMapper;
    this.renderer = renderer;
    this.deadLetters = deadLetters;
    this.halt = halt;
    this.containers = containers;
  }

  public static final String LISTENER_ID = "statement-job-listener";

  @RabbitListener(
      id = LISTENER_ID,
      queues = StatementJobsTopic.NAME,
      containerFactory = "statementJobListenerContainerFactory")
  public void onMessage(Message message, Channel channel) throws Exception {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    try {
      dispatchWithRetry(message);
      channel.basicAck(deliveryTag, false);
    } catch (ParticipantNotEnrolledException poison) {
      haltOn(message, DeadLetterPublisher.REASON_PARTICIPANT_NOT_ENROLLED, poison);
      channel.basicReject(deliveryTag, false);
      throw poison;
    } catch (MalformedStatementJobException malformed) {
      haltOn(message, DeadLetterPublisher.REASON_MALFORMED, malformed);
      channel.basicReject(deliveryTag, false);
      throw malformed;
    }
  }

  private void dispatchWithRetry(Message message) {
    Duration backoff = Duration.ofSeconds(1);
    Duration maxBackoff = Duration.ofSeconds(30);
    while (!Thread.currentThread().isInterrupted()) {
      try {
        dispatch(message);
        return;
      } catch (ParticipantNotEnrolledException | MalformedStatementJobException poison) {
        throw poison;
      } catch (Exception transientEx) {
        LOGGER.warn(
            "transient failure rendering statement job: {} - retrying in {}",
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
    StatementJob job = StatementJob.fromJson(objectMapper, body);
    renderer.render(job);
  }

  private void haltOn(Message message, String reason, RuntimeException cause) {
    boolean preserved = deadLetters.publish(message, reason, cause.getMessage());
    halt.trigger(
        "poison job at %s deliveryTag %d (%s): %s"
            .formatted(
                message.getMessageProperties().getConsumerQueue(),
                message.getMessageProperties().getDeliveryTag(),
                reason,
                preserved
                    ? "preserved to the dead-letter queue"
                    : "NOT preserved; restart retries"));
    containers.stopWorkerContainers();
    LOGGER.error("statement render halted on a poison job; readiness will report DOWN");
  }
}
