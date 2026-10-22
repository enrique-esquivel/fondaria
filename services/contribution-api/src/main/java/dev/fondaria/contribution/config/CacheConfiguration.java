package dev.fondaria.contribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.contribution.cashreceipt.CashReceiptResponseCache;
import dev.fondaria.contribution.cashreceipt.RedisCashReceiptResponseCache;
import dev.fondaria.contribution.cashreceipt.RedisMetrics;
import dev.fondaria.contribution.cashreceipt.TraceCorrelationPublisher;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires the cash-receipt response cache. Lettuce directly, not {@code spring-boot-starter-data-
 * redis}: this service needs one {@code GET} and one {@code SETEX}, and a template, a serializer
 * stack and a repository abstraction on top of that would be scaffolding around two commands.
 *
 * <p><b>{@code REJECT_COMMANDS} while disconnected is the important line here.</b> Lettuce's
 * default is to queue commands until the connection returns or the timeout expires, which turns a
 * stopped Redis into a delay on every request — precisely the coupling this endpoint promises not
 * to have. Rejecting immediately makes a cache outage a fast miss instead of a slow one.
 *
 * <p>One connection, shared: a Lettuce {@code StatefulRedisConnection} is thread-safe and
 * multiplexes, so a pool would add contention rather than remove it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

  @Bean(destroyMethod = "shutdown")
  public RedisClient redisClient(CacheProperties properties) {
    properties.requireComplete();
    // Built from the URL plus a separately-supplied password, rather than from a URL
    // with credentials in it. Lettuce's credentials-provider API hands back a Reactor
    // Mono, which is banned without an ADR (AGENTS.md §4), so the builder is used.
    RedisURI uri =
        RedisURI.builder(RedisURI.create(properties.url()))
            .withPassword(properties.password().toCharArray())
            .withTimeout(properties.commandTimeout())
            .build();
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    client.setDefaultTimeout(properties.commandTimeout());
    return client;
  }

  /**
   * <b>{@code @Lazy}, and it has to be.</b> {@code connect()} opens a socket, so an eager
   * connection makes a Redis that happens to be down at start-up stop this service from starting —
   * meaning a pod restarting during a cache outage crash-loops and no cash receipt can be posted at
   * all, because of a cache. That is precisely the coupling this endpoint promises not to have.
   * Deferred, the first cache call fails, is swallowed as a miss, and the connection is attempted
   * again on the next one, so Redis coming back needs no restart.
   */
  @Bean(destroyMethod = "close")
  @Lazy
  public StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
    return client.connect();
  }

  /**
   * The injection point is {@code @Lazy} as well as the bean definition. Marking only the
   * definition would be undone here: injecting it into an eager bean is what forces it to be
   * created.
   */
  @Bean
  public CashReceiptResponseCache cashReceiptResponseCache(
      @Lazy StatefulRedisConnection<String, String> connection,
      ObjectMapper objectMapper,
      CacheProperties properties,
      RedisMetrics metrics) {
    return new RedisCashReceiptResponseCache(
        connection, objectMapper, properties.cashReceiptTtl(), metrics);
  }

  /**
   * Task 11.2. The same lazy connection as the response cache above, for the same reason: an eager
   * connection would make a Redis that happens to be down at start-up stop this service from
   * starting, over a correlation entry nothing downstream of the ledger post depends on.
   */
  @Bean
  public TraceCorrelationPublisher traceCorrelationPublisher(
      @Lazy StatefulRedisConnection<String, String> connection,
      Tracer tracer,
      CacheProperties properties,
      RedisMetrics metrics) {
    return new TraceCorrelationPublisher(
        connection, tracer, properties.traceCorrelationTtl(), metrics);
  }
}
