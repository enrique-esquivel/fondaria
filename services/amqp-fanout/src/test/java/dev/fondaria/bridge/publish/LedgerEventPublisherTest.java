package dev.fondaria.bridge.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.transform.LedgerRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitOperations.OperationsCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * {@link LedgerEventPublisher} under slow and refused confirms (ADR-0041): a batch is published on
 * one channel, its confirms are awaited against one deadline, and a batch that is not fully
 * confirmed closes its channel rather than returning it to the cache owing confirms — the leak that
 * took one connection to 2047 channels on load run 8.
 */
class LedgerEventPublisherTest {

  private static final int BATCH = 96;

  private final RabbitTemplate template = mock(RabbitTemplate.class);
  private final RabbitOperations ops = mock(RabbitOperations.class);
  private final ChannelProxy channel = mock(ChannelProxy.class);
  private final AtomicBoolean closedPhysically = new AtomicBoolean();

  @Test
  void slowConfirmsFailTheBatchAfterOneTimeoutAndCloseItsOnlyChannel() {
    confirmEachSend(correlation -> {}); // the broker never confirms
    LedgerEventPublisher publisher = publisher(Duration.ofMillis(200));

    long start = System.nanoTime();
    assertThatThrownBy(() -> publisher.publishAll(records(BATCH)))
        .isInstanceOf(ProducerConfirmException.class);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(took)
        .as("one deadline for the batch, not one timeout per record (96 x 200 ms)")
        .isLessThan(Duration.ofSeconds(3));
    verify(template, times(1)).invoke(any());
    verify(ops, times(BATCH))
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    assertThat(closedPhysically).as("the unconfirmed channel is closed, not cached").isTrue();
    assertThat(RabbitUtils.isPhysicalCloseRequired()).as("no flag left on the thread").isFalse();
  }

  @Test
  void aNackedRecordFailsTheBatchAndClosesItsChannel() {
    List<Integer> sent = new ArrayList<>();
    confirmEachSend(
        correlation -> {
          sent.add(sent.size());
          boolean ack = sent.size() != 40;
          correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
        });

    assertThatThrownBy(() -> publisher(Duration.ofSeconds(5)).publishAll(records(BATCH)))
        .isInstanceOf(ProducerConfirmException.class)
        .hasMessageContaining("nack");
    assertThat(closedPhysically).isTrue();
  }

  @Test
  void aFullyConfirmedBatchReturnsItsChannelToTheCache() {
    confirmEachSend(
        correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

    publisher(Duration.ofSeconds(5)).publishAll(records(BATCH));

    verify(template, times(1)).invoke(any());
    assertThat(closedPhysically).isFalse();
  }

  @Test
  void noChannelWithinTheCheckoutTimeoutIsATransientFailure() {
    when(template.invoke(any())).thenThrow(new AmqpTimeoutException("No available channels"));

    assertThatThrownBy(() -> publisher(Duration.ofSeconds(5)).publishAll(records(3)))
        .isInstanceOf(ProducerConfirmException.class)
        .hasMessageContaining("No available channels");
  }

  /** Runs the publisher's invoke scope on {@link #ops}, as RabbitTemplate does on one channel. */
  @SuppressWarnings("unchecked")
  private void confirmEachSend(Consumer<CorrelationData> broker) {
    when(template.invoke(any()))
        .thenAnswer(
            invocation -> {
              Object result =
                  ((OperationsCallback<Object>) invocation.getArgument(0)).doInRabbit(ops);
              // What RabbitTemplate's cleanup would see as it closes the channel.
              closedPhysically.set(RabbitUtils.isPhysicalCloseRequired());
              return result;
            });
    doAnswer(
            invocation -> {
              broker.accept(invocation.getArgument(3));
              return null;
            })
        .when(ops)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    when(ops.execute(any()))
        .thenAnswer(
            invocation ->
                ((ChannelCallback<Object>) invocation.getArgument(0)).doInRabbit(channel));
  }

  private LedgerEventPublisher publisher(Duration confirmTimeout) {
    return new LedgerEventPublisher(
        template,
        new ObjectMapper(),
        new BridgeProperties("rabbitmq", 64, confirmTimeout, Duration.ZERO, 32, null),
        new RabbitMetrics(new SimpleMeterRegistry()));
  }

  private static List<LedgerRecord> records(int count) {
    List<LedgerRecord> records = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      records.add(new LedgerRecord(LedgerTopics.TRANSFERS, "key-" + i, Map.of("n", i)));
    }
    return records;
  }
}
