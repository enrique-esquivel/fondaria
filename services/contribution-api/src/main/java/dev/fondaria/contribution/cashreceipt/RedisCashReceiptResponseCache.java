package dev.fondaria.contribution.cashreceipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CashReceiptResponseCache} over Redis, via Lettuce.
 *
 * <p><b>Every failure is swallowed and logged, and that is the design, not laziness.</b> A cache
 * whose outage can fail a request is not a cache. Nothing here is consulted before a decision that
 * money depends on: a hit short-circuits a receipt whose outcome PostgreSQL and the ledger have
 * already agreed on, and a miss simply asks them again.
 *
 * <p><b>One {@code SETEX} and one {@code GET}, no Lua.</b> Both are single atomic commands, so
 * there is nothing here for a script to make atomic. A Lua script is required for a read-then-write
 * (AGENTS.md §4); this has none, because it never computes anything — see {@link
 * CashReceiptResponseCache}.
 *
 * <p><b>The serialized form writes the amount as an integer.</b> {@code putLong}/{@code asLong},
 * never a floating-point node and never a decimal string. Jackson would happily read {@code
 * 2500.00} back as a double, and a cache is exactly the sort of place a money value quietly becomes
 * one.
 */
public class RedisCashReceiptResponseCache implements CashReceiptResponseCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(RedisCashReceiptResponseCache.class);

  /**
   * Versioned, so a change to the serialized form below cannot be read by a running instance as
   * though it were the old one. A new prefix simply misses and re-reads from PostgreSQL.
   */
  private static final String KEY_PREFIX = "cash-receipt:v1:";

  private final StatefulRedisConnection<String, String> connection;
  private final ObjectMapper objectMapper;
  private final Duration ttl;
  private final RedisMetrics metrics;

  public RedisCashReceiptResponseCache(
      StatefulRedisConnection<String, String> connection,
      ObjectMapper objectMapper,
      Duration ttl,
      RedisMetrics metrics) {
    this.connection = connection;
    this.objectMapper = objectMapper;
    this.ttl = ttl;
    this.metrics = metrics;
  }

  @Override
  public Optional<CashReceipt> find(TransferId transferId) {
    long start = System.nanoTime();
    try {
      String payload = connection.sync().get(key(transferId));
      Optional<CashReceipt> result =
          payload == null ? Optional.empty() : Optional.of(deserialize(payload));
      metrics.requestCompleted("find", System.nanoTime() - start, false);
      return result;
    } catch (RuntimeException e) {
      // Deliberately not rethrown, and deliberately not logged with the payload: a
      // corrupt or unreachable cache degrades this request to a PostgreSQL read.
      metrics.requestCompleted("find", System.nanoTime() - start, true);
      LOGGER.warn("Cash receipt cache unavailable on read; falling back to PostgreSQL", e);
      return Optional.empty();
    }
  }

  @Override
  public void store(CashReceipt receipt) {
    if (!receipt.isResolved()) {
      throw new IllegalArgumentException("Only a resolved cash receipt may be cached");
    }
    long start = System.nanoTime();
    try {
      connection.sync().setex(key(receipt.transferId()), ttl.toSeconds(), serialize(receipt));
      metrics.requestCompleted("store", System.nanoTime() - start, false);
    } catch (RuntimeException e) {
      metrics.requestCompleted("store", System.nanoTime() - start, true);
      LOGGER.warn("Cash receipt cache unavailable on write; the next retry re-reads it", e);
    }
  }

  private static String key(TransferId transferId) {
    return KEY_PREFIX + TransferIdDeriver.toUuid(transferId);
  }

  private String serialize(CashReceipt receipt) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("organizationId", receipt.organizationId().toString());
    node.put("idempotencyKey", receipt.idempotencyKey());
    node.put("transferId", TransferIdDeriver.toUuid(receipt.transferId()).toString());
    node.put("amountMinorUnits", receipt.amount().cents());
    node.put("currency", receipt.amount().currency().name());
    node.put("valueDate", receipt.valueDate().toString());
    node.put("bankReference", receipt.bankReference());
    node.put("operatorId", receipt.operatorId());
    node.put("requestFingerprint", receipt.requestFingerprint());
    node.put("status", receipt.status().name());
    node.put("rejectionReason", receipt.rejectionReason().orElse(null));
    node.put("createdAt", receipt.createdAt().toString());
    node.put("updatedAt", receipt.updatedAt().toString());
    try {
      return objectMapper.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cash receipt could not be serialized for the cache", e);
    }
  }

  private CashReceipt deserialize(String payload) {
    try {
      JsonNode node = objectMapper.readTree(payload);
      return new CashReceipt(
          new OrganizationId(UUID.fromString(node.get("organizationId").asText())),
          node.get("idempotencyKey").asText(),
          TransferIdDeriver.toTransferId(UUID.fromString(node.get("transferId").asText())),
          Money.of(
              node.get("amountMinorUnits").asLong(),
              CurrencyCode.valueOf(node.get("currency").asText())),
          LocalDate.parse(node.get("valueDate").asText()),
          node.get("bankReference").asText(),
          node.get("operatorId").asText(),
          node.get("requestFingerprint").asText(),
          CashReceiptStatus.parse(node.get("status").asText()),
          Optional.ofNullable(
              node.get("rejectionReason").isNull() ? null : node.get("rejectionReason").asText()),
          Instant.parse(node.get("createdAt").asText()),
          Instant.parse(node.get("updatedAt").asText()));
    } catch (Exception e) {
      throw new IllegalStateException("Cached cash receipt could not be read", e);
    }
  }
}
