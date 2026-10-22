package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Asserts fixture reuse rather than any behaviour that handles money, so it is tagged {@code
 * integration}. Whichever test touches a fixture first pays that container's startup, and on a cold
 * Docker that lands here — this class alone accounted for roughly 22 s of the default build before
 * it was tagged. Pairs with {@link ContainerReuseFirstTest}.
 */
@Tag("integration")
class ContainerReuseSecondTest {

  @Test
  void testVerifyAllContainersMatchRegistry() {
    assertThat(PostgresFixture.getInstance().getContainerId())
        .as("Postgres container instance must be reused across test classes")
        .isEqualTo(ContainerReuseRegistry.getPostgresId());

    assertThat(RedisFixture.getInstance().getContainerId())
        .as("Redis container instance must be reused across test classes")
        .isEqualTo(ContainerReuseRegistry.getRedisId());

    assertThat(RabbitMqFixture.getInstance().getContainerId())
        .as("RabbitMQ container instance must be reused across test classes")
        .isEqualTo(ContainerReuseRegistry.getRabbitMqId());

    assertThat(ObjectStoreFixture.getInstance().getContainerId())
        .as("Object store container instance must be reused across test classes")
        .isEqualTo(ContainerReuseRegistry.getObjectStoreId());

    assertThat(TigerBeetleFixture.getInstance().getContainerId())
        .as("TigerBeetle container instance must be reused across test classes")
        .isEqualTo(ContainerReuseRegistry.getTigerBeetleId());
  }
}
