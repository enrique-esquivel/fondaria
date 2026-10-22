package dev.fondaria.bridge.transform;

/**
 * One AMQP message the fanout service is about to publish: target exchange, the routing key, and
 * the payload to serialise. A {@code null} or empty key is allowed; a payload is always present.
 */
public record LedgerRecord(String topic, String key, Object payload) {}
