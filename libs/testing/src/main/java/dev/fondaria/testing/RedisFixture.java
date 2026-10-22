package dev.fondaria.testing;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Singleton fixture for cache (Valkey/Redis) container. Starts once per JVM and provides reset via
 * FLUSHALL.
 */
public final class RedisFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(RedisFixture.class);
  private static volatile RedisFixture instance;

  private final GenericContainer<?> container;

  private RedisFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting Valkey container with image {}", ContainerImages.REDIS);
    this.container =
        new GenericContainer<>(ContainerImages.REDIS)
            .withExposedPorts(6379)
            .withCommand("redis-server --requirepass funds_dev_pass")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
            .withReuse(true)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info("Valkey container started at {}:{}", getHost(), getPort());
  }

  public static RedisFixture getInstance() {
    if (instance == null) {
      synchronized (RedisFixture.class) {
        if (instance == null) {
          instance = new RedisFixture();
        }
      }
    }
    return instance;
  }

  public String getContainerId() {
    return container.getContainerId();
  }

  public String getHost() {
    return container.getHost();
  }

  public int getPort() {
    return container.getMappedPort(6379);
  }

  public String getPassword() {
    return "funds_dev_pass";
  }

  public String getRedisUrl() {
    return "redis://:" + getPassword() + "@" + getHost() + ":" + getPort();
  }

  public synchronized void reset() {
    LOGGER.info("Resetting Redis data via FLUSHALL...");
    try (RedisClient client = RedisClient.create(getRedisUrl());
        StatefulRedisConnection<String, String> connection = client.connect()) {
      RedisCommands<String, String> syncCommands = connection.sync();
      syncCommands.flushall();
    } catch (Exception e) {
      throw new RuntimeException("Failed to reset Redis container", e);
    }
  }
}
