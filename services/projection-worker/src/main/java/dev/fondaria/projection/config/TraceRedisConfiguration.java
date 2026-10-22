package dev.fondaria.projection.config;

import dev.fondaria.projection.consume.RedisMetrics;
import dev.fondaria.projection.consume.TraceCorrelationReader;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Task 11.2's optional trace correlation. One branching bean, not
 * {@code @ConditionalOnProperty} — {@code url: ${TRACE_CORRELATION_REDIS_URL:}} leaves the property
 * present but empty when unset, and the annotation without a {@code havingValue} would match any
 * non-{@code "false"} value. Same reasoning as {@code valuation-api}'s {@code
 * NavCacheConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TraceRedisProperties.class)
public class TraceRedisConfiguration {

  private static final Logger LOGGER = LoggerFactory.getLogger(TraceRedisConfiguration.class);

  @Bean(destroyMethod = "close")
  public TraceCorrelationReader traceCorrelationReader(
      TraceRedisProperties properties, Tracer tracer, RedisMetrics metrics) {
    properties.requireCoherent();
    if (!properties.enabled()) {
      LOGGER.info(
          "No {} configured; CDC-boundary trace links are disabled",
          TraceRedisProperties.URL_VARIABLE);
      return TraceCorrelationReader.NONE;
    }
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
    return new TraceCorrelationReader(client, tracer, metrics);
  }
}
