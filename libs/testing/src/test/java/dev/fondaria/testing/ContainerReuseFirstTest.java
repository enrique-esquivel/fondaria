package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Asserts fixture reuse rather than any behaviour that handles money, so it is tagged {@code
 * integration}. Pairs with {@link ContainerReuseSecondTest} — both must run for the assertion to
 * mean anything, which {@code make verify-all} does.
 */
@Tag("integration")
class ContainerReuseFirstTest {

  @Test
  void testVerifyPostgresAndRedisContainerReuse() {
    String postgresContainerId = PostgresFixture.getInstance().getContainerId();
    String redisContainerId = RedisFixture.getInstance().getContainerId();

    assertThat(postgresContainerId)
        .as("PostgreSQL container ID must match shared fixture instance")
        .isEqualTo(ContainerReuseRegistry.getPostgresId());

    assertThat(redisContainerId)
        .as("Redis container ID must match shared fixture instance")
        .isEqualTo(ContainerReuseRegistry.getRedisId());
  }
}
