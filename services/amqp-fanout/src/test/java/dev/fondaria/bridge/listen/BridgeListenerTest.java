package dev.fondaria.bridge.listen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dev.fondaria.bridge.cdc.CdcMessageParser;
import dev.fondaria.bridge.config.BridgeProperties;
import dev.fondaria.bridge.publish.DlqEnvelope;
import dev.fondaria.bridge.publish.LedgerEventPublisher;
import dev.fondaria.bridge.publish.ProducerConfirmException;
import dev.fondaria.bridge.publish.RabbitMetrics;
import dev.fondaria.bridge.transform.LedgerEventTransform;
import dev.fondaria.bridge.transform.LedgerRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * {@link BridgeListener}'s batch contract: one publish of every record, then one multiple-ack — or,
 * when RabbitMQ does not confirm, the batch held and published again, never nacked (ADR-0041).
 */
class BridgeListenerTest {

  private final LedgerEventPublisher publisher = mock(LedgerEventPublisher.class);
  private final Channel channel = mock(Channel.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RabbitMetrics metrics = new RabbitMetrics(registry);
  private final BridgeListener listener =
      new BridgeListener(
          new CdcMessageParser(new ObjectMapper()),
          new LedgerEventTransform(),
          publisher,
          Clock.systemUTC(),
          metrics,
          new BridgeProperties("rabbitmq", 64, Duration.ofSeconds(30), Duration.ZERO, 32, null));

  @Test
  void aBatchIsPublishedTogetherAndAcknowledgedOnceUpToItsLastTag() throws Exception {
    List<Message> batch = new ArrayList<>();
    for (long tag = 11; tag <= 20; tag++) {
      batch.add(delivery(tag, changeEvent()));
    }

    listener.onBatch(batch, channel);

    ArgumentCaptor<List<LedgerRecord>> records = recordsCaptor();
    verify(publisher).publishBatch(records.capture(), anyList());
    assertThat(records.getValue())
        .as("a cash event is one transfer and no entry (ADR-0040)")
        .hasSize(10);
    verify(channel).basicAck(20L, true);
    verifyNoMoreInteractions(channel);
  }

  @Test
  void aPoisonMessageGoesToTheDlqInTheSamePublishAndIsAcknowledgedWithTheBatch() throws Exception {
    String marker = "poison-" + UUID.randomUUID();
    List<Message> batch =
        List.of(
            delivery(1, changeEvent()),
            delivery(2, "{\"marker\":\"" + marker + "\"}"),
            delivery(3, changeEvent()));

    listener.onBatch(batch, channel);

    ArgumentCaptor<List<LedgerRecord>> records = recordsCaptor();
    ArgumentCaptor<List<DlqEnvelope>> dead = dlqCaptor();
    verify(publisher).publishBatch(records.capture(), dead.capture());
    assertThat(records.getValue()).as("the two good events around it still go").hasSize(2);
    assertThat(dead.getValue())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.reason()).isEqualTo(DlqEnvelope.REASON_UNPARSEABLE);
              assertThat(e.originalBody()).contains(marker);
            });
    verify(channel).basicAck(3L, true);
  }

  @Test
  void anUnconfirmedPublishHoldsTheBatchAndRetriesPastTheDeliveryLimitWithoutEverNacking()
      throws Exception {
    // bridge.ledger's x-delivery-limit is 5: under the old nack-and-requeue, the sixth failure
    // dead-lettered the batch (load run 8: 64,832 events). Fail more often than that.
    when(channel.isOpen()).thenReturn(true);
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<Double> haltedWhileFailing = new AtomicReference<>();
    doAnswer(
            invocation -> {
              if (attempts.incrementAndGet() <= 8) {
                haltedWhileFailing.set(registry.get("bridge_publish_halted").gauge().value());
                throw new ProducerConfirmException("forced");
              }
              return null;
            })
        .when(publisher)
        .publishBatch(anyList(), anyList());

    listener.onBatch(List.of(delivery(7, changeEvent()), delivery(9, changeEvent())), channel);

    assertThat(attempts).hasValue(9);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    verify(channel, never()).basicReject(anyLong(), anyBoolean());
    verify(channel).basicAck(9L, true);
    assertThat(haltedWhileFailing.get()).as("halted while the broker refused").isEqualTo(1.0);
    assertThat(registry.get("bridge_publish_halted").gauge().value())
        .as("resumed once confirmed")
        .isZero();
  }

  @Test
  void aClosedConsumerChannelEndsTheRetryWithoutAcknowledgingOrNacking() throws Exception {
    when(channel.isOpen()).thenReturn(true, true, false);
    doThrow(new ProducerConfirmException("forced"))
        .when(publisher)
        .publishBatch(anyList(), anyList());

    assertThatThrownBy(() -> listener.onBatch(List.of(delivery(3, changeEvent())), channel))
        .isInstanceOf(ProducerConfirmException.class);

    verify(publisher, times(3)).publishBatch(anyList(), anyList());
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    assertThat(registry.get("bridge_publish_halted").gauge().value()).isZero();
  }

  @Test
  void anEmptyBatchTouchesNothing() throws Exception {
    listener.onBatch(List.of(), channel);

    verifyNoMoreInteractions(channel, publisher);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<LedgerRecord>> recordsCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<List<DlqEnvelope>> dlqCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
  }

  private static Message delivery(long tag, String body) {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(tag);
    properties.setReceivedExchange("tigerbeetle");
    properties.setReceivedRoutingKey("");
    return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
  }

  private static String changeEvent() {
    String transferId = id();
    String debit = id();
    String credit = id();
    return "{\"timestamp\":\"1788559800000000000\",\"type\":\"single_phase\",\"ledger\":484,"
        + "\"transfer\":{\"id\":\""
        + transferId
        + "\",\"amount\":\"150000\",\"pending_id\":\"0\",\"user_data_128\":\"0\","
        + "\"user_data_64\":\"0\",\"user_data_32\":0,\"timeout\":0,\"code\":10,\"flags\":0,"
        + "\"timestamp\":\"1\"},\"debit_account\":{\"id\":\""
        + debit
        + "\",\"debits_pending\":\"0\",\"debits_posted\":\"150000\",\"credits_pending\":\"0\","
        + "\"credits_posted\":\"0\",\"user_data_128\":\"0\",\"user_data_64\":\"0\","
        + "\"user_data_32\":0,\"code\":1,\"flags\":0,\"timestamp\":\"1\"},"
        + "\"credit_account\":{\"id\":\""
        + credit
        + "\",\"debits_pending\":\"0\",\"debits_posted\":\"0\",\"credits_pending\":\"0\","
        + "\"credits_posted\":\"150000\",\"user_data_128\":\"0\",\"user_data_64\":\"0\","
        + "\"user_data_32\":0,\"code\":1,\"flags\":0,\"timestamp\":\"1\"}}";
  }

  private static String id() {
    return Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits() >>> 1);
  }
}
