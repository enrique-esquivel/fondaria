package dev.fondaria.bridge.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code bridge.ledger} listener container: Spring Boot's {@code
 * spring.rabbitmq.listener.simple} settings (manual acknowledgement, {@code
 * BRIDGE_AMQP_CONCURRENCY} consumers, {@code BRIDGE_AMQP_PREFETCH}, {@code missing-queues-fatal:
 * false}), plus consumer-side batching.
 *
 * <p>A consumer hands {@link dev.fondaria.bridge.listen.BridgeListener} up to {@link
 * BridgeProperties#batchSize()} messages at once, collecting until the batch is full or no message
 * arrives within {@link BridgeProperties#batchWait()}. During a drain the prefetch buffer is full,
 * so batches are full; on an idle feed one event waits at most {@code batchWait}.
 */
@Configuration(proxyBeanMethods = false)
public class BridgeListenerConfiguration {

  public static final String CONTAINER_FACTORY = "bridgeListenerContainerFactory";

  @Bean(CONTAINER_FACTORY)
  public SimpleRabbitListenerContainerFactory bridgeListenerContainerFactory(
      SimpleRabbitListenerContainerFactoryConfigurer configurer,
      ConnectionFactory connectionFactory,
      BridgeProperties properties) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    // Also makes the factory a batch listener factory (Spring AMQP 3): the listener method
    // receives a List<Message>.
    factory.setConsumerBatchEnabled(true);
    factory.setBatchSize(properties.batchSize());
    factory.setReceiveTimeout(properties.batchWait().toMillis());
    return factory;
  }
}
