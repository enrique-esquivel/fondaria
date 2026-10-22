package dev.fondaria.eodjob.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The service's one {@link DataSource}, and the {@link JdbcClient} every repository uses.
 *
 * <p>JDBC and {@code JdbcClient} by name (docs/ENGINEERING-RULES.md §5): no ORM, no JPA, no R2DBC.
 * Flyway's autoconfiguration picks this bean up and migrates against it at startup, so a service
 * instance cannot serve traffic against a schema it has not migrated.
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
    config.setPoolName("fondaria-eod-job");
    return new HikariDataSource(config);
  }

  @Bean
  public JdbcClient jdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }
}
