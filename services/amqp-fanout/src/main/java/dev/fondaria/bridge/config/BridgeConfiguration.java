package dev.fondaria.bridge.config;

import dev.fondaria.bridge.publish.LedgerTopics;
import java.time.Duration;
import java.util.Map;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ exchanges the fan-out service produces to, and fails startup fast if a
 * required broker connection variable is absent.
 *
 * <p>Exchanges declared:
 *
 * <ul>
 *   <li>{@code ledger.transfers.v1} — durable fanout exchange.
 *   <li>{@code ledger.entries.v1} — durable {@code x-consistent-hash} exchange.
 *   <li>{@code ledger.entries.dlq} — durable queue (and default routing).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class BridgeConfiguration {

  public BridgeConfiguration(BridgeProperties properties) {
    properties.requireComplete();
  }

  /**
   * How long a publish waits for a free channel before failing as transient. Longer than a batch's
   * publish takes, shorter than a channel closing with missing confirms (Spring's 30 s close
   * timeout), so a stuck checkout surfaces as a retry rather than an unbounded wait.
   */
  static final Duration PUBLISH_CHANNEL_CHECKOUT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Publishes on a connection of its own with at most one channel per consumer (ADR-0041).
   *
   * <p>Each consumer publishes a batch on one channel, so the publisher needs exactly {@code
   * BRIDGE_AMQP_CONCURRENCY} channels. With a checkout timeout the channel cache size is a hard
   * limit rather than a cache: a channel that is still closing keeps its permit, and a checkout
   * that finds none fails after the timeout instead of opening another. Load run 8 had no limit and
   * one connection reached RabbitMQ's 2047 channels.
   *
   * <p>Set here, not through {@code spring.rabbitmq.cache.channel.*}: those apply to the consumers'
   * connection too, where RabbitAdmin needs one channel beyond the consumers' to declare on every
   * new connection; held to the concurrency, it starved and no publisher connection could open. A
   * separate publisher connection also keeps acknowledgements flowing while RabbitMQ blocks
   * publishers under a resource alarm.
   */
  @Bean
  public RabbitTemplateCustomizer boundedPublisherConnection(RabbitProperties rabbit) {
    int channels = publisherChannels(rabbit);
    return template -> {
      template.setUsePublisherConnection(true);
      if (template.getConnectionFactory().getPublisherConnectionFactory()
          instanceof CachingConnectionFactory publisher) {
        publisher.setChannelCacheSize(channels);
        publisher.setChannelCheckoutTimeout(PUBLISH_CHANNEL_CHECKOUT_TIMEOUT.toMillis());
      }
    };
  }

  /** One publish channel per consumer the listener container can run. */
  static int publisherChannels(RabbitProperties rabbit) {
    RabbitProperties.SimpleContainer simple = rabbit.getListener().getSimple();
    Integer consumers =
        simple.getMaxConcurrency() != null ? simple.getMaxConcurrency() : simple.getConcurrency();
    return consumers == null || consumers < 1 ? 1 : consumers;
  }

  @Bean
  public Declarables ledgerExchanges() {
    FanoutExchange transfersExchange = new FanoutExchange(LedgerTopics.TRANSFERS, true, false);
    CustomExchange entriesExchange =
        new CustomExchange(LedgerTopics.ENTRIES, "x-consistent-hash", true, false, Map.of());
    Queue dlq = new Queue(LedgerTopics.ENTRIES_DLQ, true);
    return new Declarables(transfersExchange, entriesExchange, dlq);
  }
}
