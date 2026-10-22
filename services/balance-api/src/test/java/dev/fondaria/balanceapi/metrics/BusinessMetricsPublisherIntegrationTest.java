package dev.fondaria.balanceapi.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.common.FundId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.testing.PostgresFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The metrics sweep against a real PostgreSQL through a real, deliberately small Hikari pool.
 *
 * <p>Regression: the fund-metadata query once returned an unclosed {@code JdbcClient} stream, which
 * holds its connection until closed. Every 15-second sweep leaked one connection, and after ten
 * sweeps the service's pool was exhausted and every balance endpoint returned 503. Both queries in
 * the sweep swallow their own failures, so the leak is invisible in {@code publishMetrics()}'s
 * result — it has to be asserted on the pool itself.
 */
@Tag("integration")
class BusinessMetricsPublisherIntegrationTest {

  private static final int POOL_SIZE = 2;

  @Test
  @DisplayName("repeated sweeps return every connection to the pool")
  void repeatedSweepsDoNotLeakConnections() throws Exception {
    FundId fundId = new FundId(UUID.randomUUID());
    FundDirectory fundDirectory = () -> List.of(new RegisteredFund(fundId, 1_001L));

    try (HikariDataSource pool = smallPool()) {
      BusinessMetricsPublisher publisher =
          new BusinessMetricsPublisher(
              new SimpleMeterRegistry(),
              fundDirectory,
              new FakeNavCache(),
              new FakeLedgerPort(),
              new AccountIdDeriver(UUID.randomUUID()),
              JdbcClient.create(pool),
              Clock.systemUTC());

      for (int sweep = 0; sweep < POOL_SIZE * 3; sweep++) {
        publisher.publishMetrics();
      }

      assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
      try (Connection connection = pool.getConnection()) {
        assertThat(connection.isValid(1)).isTrue();
      }
    }
  }

  /** A pool this test owns, small enough that a per-sweep leak exhausts it within the loop. */
  private static HikariDataSource smallPool() {
    PostgresFixture postgres = PostgresFixture.getInstance();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(POOL_SIZE);
    config.setConnectionTimeout(1_000);
    config.setPoolName("balance-api-business-metrics-leak-test");
    return new HikariDataSource(config);
  }
}
