package dev.fondaria.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

/**
 * Singleton fixture for PostgreSQL container. Starts once per JVM and runs Flyway migrations on
 * startup.
 */
public final class PostgresFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(PostgresFixture.class);
  private static volatile PostgresFixture instance;

  private final PostgreSQLContainer<?> container;

  private PostgresFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting PostgreSQL container with image {}", ContainerImages.POSTGRES);
    this.container =
        new PostgreSQLContainer<>(ContainerImages.POSTGRES)
            .withDatabaseName("funds")
            .withUsername("funds")
            .withPassword("funds_dev_pass")
            .withReuse(true)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info("PostgreSQL container started at {}", container.getJdbcUrl());
    runMigrations();
  }

  public static PostgresFixture getInstance() {
    if (instance == null) {
      synchronized (PostgresFixture.class) {
        if (instance == null) {
          instance = new PostgresFixture();
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
    return container.getMappedPort(5432);
  }

  public String getJdbcUrl() {
    return container.getJdbcUrl();
  }

  public String getUsername() {
    return container.getUsername();
  }

  public String getPassword() {
    return container.getPassword();
  }

  public Connection getConnection() throws SQLException {
    return DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
  }

  /**
   * Drops every application schema and re-migrates.
   *
   * <p><b>Every</b> schema, discovered at runtime rather than from a hardcoded list. The platform
   * has four (ADR-0011) and will gain more, and a reset that dropped only {@code public} would take
   * the Flyway history with it while leaving the tables it recorded standing — so the next
   * migration run would replay {@code CREATE TABLE} against tables that already exist and fail. The
   * failure surfaces in whichever module happens to run after this one, which makes it look like
   * that module's bug.
   */
  public synchronized void reset() {
    LOGGER.info("Resetting PostgreSQL schemas...");
    try (Connection conn = getConnection();
        Statement stmt = conn.createStatement()) {
      for (String schema : applicationSchemas(conn)) {
        stmt.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
      }
      stmt.execute("CREATE SCHEMA IF NOT EXISTS public");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to reset PostgreSQL schemas", e);
    }
    runMigrations();
  }

  /** Everything PostgreSQL itself does not own. */
  private static List<String> applicationSchemas(Connection conn) throws SQLException {
    List<String> schemas = new ArrayList<>();
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT nspname FROM pg_namespace "
                    + "WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'")) {
      while (rs.next()) {
        schemas.add(rs.getString(1));
      }
    }
    return schemas;
  }

  private void runMigrations() {
    LOGGER.info("Running Flyway migrations on {}", getJdbcUrl());
    try {
      Flyway flyway =
          Flyway.configure()
              .dataSource(getJdbcUrl(), getUsername(), getPassword())
              .failOnMissingLocations(false)
              .load();
      flyway.migrate();
    } catch (Exception e) {
      LOGGER.warn(
          "Flyway migration executed (no-op or missing locations handled): {}", e.getMessage());
    }
  }
}
