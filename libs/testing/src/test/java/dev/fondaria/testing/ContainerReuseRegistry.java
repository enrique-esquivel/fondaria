package dev.fondaria.testing;

/**
 * Shared thread-safe container ID registry for test classes. Captures container IDs on first access
 * so container reuse tests do not depend on test execution order.
 */
public final class ContainerReuseRegistry {

  private static String postgresContainerId;
  private static String redisContainerId;
  private static String rabbitmqContainerId;
  private static String objectStoreContainerId;
  private static String tigerbeetleContainerId;

  private ContainerReuseRegistry() {
    // Utility class
  }

  public static synchronized String getPostgresId() {
    if (postgresContainerId == null) {
      postgresContainerId = PostgresFixture.getInstance().getContainerId();
    }
    return postgresContainerId;
  }

  public static synchronized String getRedisId() {
    if (redisContainerId == null) {
      redisContainerId = RedisFixture.getInstance().getContainerId();
    }
    return redisContainerId;
  }

  public static synchronized String getRabbitMqId() {
    if (rabbitmqContainerId == null) {
      rabbitmqContainerId = RabbitMqFixture.getInstance().getContainerId();
    }
    return rabbitmqContainerId;
  }

  public static synchronized String getObjectStoreId() {
    if (objectStoreContainerId == null) {
      objectStoreContainerId = ObjectStoreFixture.getInstance().getContainerId();
    }
    return objectStoreContainerId;
  }

  public static synchronized String getTigerBeetleId() {
    if (tigerbeetleContainerId == null) {
      tigerbeetleContainerId = TigerBeetleFixture.getInstance().getContainerId();
    }
    return tigerbeetleContainerId;
  }
}
