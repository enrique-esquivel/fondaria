package dev.fondaria.balanceapi.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The service's one {@link DataSource}, and the {@link JdbcClient} every repository uses.
 *
 * <p>JDBC and {@code JdbcClient} by name (docs/ENGINEERING-RULES.md §5): no ORM, no JPA, no R2DBC.
 * Flyway's autoconfiguration picks this bean up and migrates against it at startup, over the shared
 * migration tree in {@code libs/db-migrations} — so this service and contribution-api converge on
 * one schema and one history rather than each carrying a private view of the database.
 *
 * <p>Every timeout is explicit (docs/ENGINEERING-RULES.md §4). A pool that waits forever for a
 * connection turns a database blip into an unbounded queue of parked virtual threads, and a
 * statement with no bound does the same thing one query at a time — which Task 6.3 made reachable,
 * since the movement history is the first request path here that reads PostgreSQL on every call.
 *
 * <p><b>The statement bound is on the {@link JdbcClient}, not on the connection.</b> Setting {@code
 * statement_timeout} through the pool would apply it to Flyway as well, and a migration is not a
 * request: bounding a table rewrite by a request-shaped budget would fail a deployment rather than
 * protect one. {@code JdbcTemplate.setQueryTimeout} reaches only the statements this application
 * issues.
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
    config.setPoolName("fondaria-balance-api");
    return new HikariDataSource(config);
  }

  @Bean
  public JdbcClient jdbcClient(DataSource dataSource, DatabaseProperties properties) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout(Math.toIntExact(properties.queryTimeout().toSeconds()));
    return JdbcClient.create(template);
  }
}
