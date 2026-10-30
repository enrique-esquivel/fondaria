package dev.fondaria.bridge.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ADR-0041: the template publishes on its own connection, held to one channel per consumer by a
 * checkout limit, while the consumers' connection keeps no such limit (RabbitAdmin needs a channel
 * beyond the consumers' to declare on each new connection).
 */
class BoundedPublisherConnectionTest {

  @Test
  void thePublisherConnectionIsHeldToOneChannelPerConsumer() {
    RabbitProperties rabbit = new RabbitProperties();
    rabbit.getListener().getSimple().setConcurrency(4);
    rabbit.getListener().getSimple().setMaxConcurrency(4);
    CachingConnectionFactory connections = new CachingConnectionFactory(new ConnectionFactory());
    RabbitTemplate template = new RabbitTemplate(connections);

    new BridgeConfiguration(new BridgeProperties("rabbitmq", 64, null, null, 32, null))
        .boundedPublisherConnection(rabbit)
        .customize(template);

    assertThat(template.isUsePublisherConnection()).isTrue();
    CachingConnectionFactory publisher =
        (CachingConnectionFactory) connections.getPublisherConnectionFactory();
    assertThat(publisher.getChannelCacheSize()).isEqualTo(4);
    assertThat(checkoutTimeout(publisher))
        .isEqualTo(BridgeConfiguration.PUBLISH_CHANNEL_CHECKOUT_TIMEOUT.toMillis());
    assertThat(checkoutTimeout(connections)).as("the consumers' connection is not capped").isZero();
  }

  @Test
  void withoutAConfiguredConcurrencyThereIsOnePublishChannel() {
    assertThat(BridgeConfiguration.publisherChannels(new RabbitProperties())).isEqualTo(1);
  }

  /** CachingConnectionFactory has a setter for the checkout timeout but no getter. */
  private static long checkoutTimeout(CachingConnectionFactory factory) {
    return (Long) ReflectionTestUtils.getField(factory, "channelCheckoutTimeout");
  }
}
