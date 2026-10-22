package dev.fondaria.statementworker.config;

import dev.fondaria.statementworker.render.RedisCompletionCounter;
import dev.fondaria.statementworker.render.RedisMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the completion counter's Redis connection (Task 7.5b, {@code ADR-0022} §3).
 *
 * <p>Lettuce directly, not {@code spring-boot-starter-data-redis}, matching {@code valuation-api}'s
 * {@code NavCacheConfiguration}: this is a Lua script and two commands, and a template plus a
 * serializer stack over that is scaffolding, not design.
 *
 * <p><b>Declared as the concrete type, and only once</b> — the same reasoning {@code
 * NavCacheConfiguration}'s javadoc gives for {@code InMemoryNavCache}. {@link
 * RedisCompletionCounter} already <i>is</i> a {@code CompletionCounter}; a second bean exposing the
 * interface would give Spring two candidates for every {@code CompletionCounter} injection point
 * and none for the concrete type the settlement sweep's {@code currentCount} read needs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfiguration {

  @Bean(destroyMethod = "close")
  public RedisCompletionCounter completionCounter(
      RedisProperties properties, RedisMetrics metrics) {
    properties.requireComplete();
    // Built from the URL plus a separately-supplied password, not a URL with credentials
    // embedded: Lettuce's credentials-provider API returns a Reactor Mono, banned without an ADR
    // (AGENTS.md §4).
    RedisURI uri =
        RedisURI.builder(RedisURI.create(properties.url()))
            .withPassword(properties.password().toCharArray())
            .withTimeout(properties.commandTimeout())
            .build();
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            // An outage is a fast miss, not a queue of commands waiting for Redis to return.
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
    client.setDefaultTimeout(properties.commandTimeout());
    return new RedisCompletionCounter(client, properties.counterTtl(), metrics);
  }
}
