package dev.fondaria.projection.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The service's one {@link DataSource}, and the {@link JdbcClient} every projection write uses.
 *
 * <p>JDBC and {@code JdbcClient} by name (docs/ENGINEERING-RULES.md §5): no ORM, no JPA, no R2DBC.
 * Flyway's autoconfiguration picks this bean up and migrates the shared tree in {@code
 * libs/db-migrations} at startup — during context refresh, which completes before the Kafka
 * listener containers start their {@code SmartLifecycle} phase. So the worker never consumes an
 * event against a schema it has not migrated ("Flyway runs at startup, before consuming" — phase-05
 * Task 5.1).
 *
 * <p>Every timeout is explicit. A pool that waits forever for a connection turns a database blip
 * into an unbounded queue of parked virtual threads.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {

  @Bean(destroyMethod = "close")
  public DataSource dataSource(DatabaseProperties properties) {
    properties.requireComplete();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(properties.url());
    config.setUsername(properties.username());
    config.setPassword(properties.password());
    config.setMaximumPoolSize(properties.maxPoolSize());
    config.setConnectionTimeout(properties.connectTimeout().toMillis());
    config.setPoolName("fondaria-projection-worker");
    return new HikariDataSource(config);
  }

  @Bean
  public JdbcClient jdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }
}
