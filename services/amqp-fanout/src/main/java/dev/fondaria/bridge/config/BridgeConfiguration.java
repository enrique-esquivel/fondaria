package dev.fondaria.bridge.config;

import dev.fondaria.bridge.publish.LedgerTopics;
import java.util.Map;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
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

  @Bean
  public Declarables ledgerExchanges() {
    FanoutExchange transfersExchange = new FanoutExchange(LedgerTopics.TRANSFERS, true, false);
    CustomExchange entriesExchange =
        new CustomExchange(LedgerTopics.ENTRIES, "x-consistent-hash", true, false, Map.of());
    Queue dlq = new Queue(LedgerTopics.ENTRIES_DLQ, true);
    return new Declarables(transfersExchange, entriesExchange, dlq);
  }
}
