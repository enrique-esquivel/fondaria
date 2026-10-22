package dev.fondaria.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * phase-04-cdc.md Task 4.3, last acceptance criterion: "The bridge has no database dependency
 * (asserted by its dependency list)."
 *
 * <p>Asserted here as classpath absence — the honest test of a dependency list. If a future change
 * adds {@code spring-boot-starter-jdbc}, Flyway, a JDBC driver, a connection pool or an ORM to this
 * module's pom, one of these classes resolves and the test fails. A bridge that can open a database
 * connection is one that can be tempted into becoming a rule engine or a second system of record
 * (docs/ENGINEERING-RULES.md §"Data flow", §"The ledger").
 */
class NoDatabaseDependencyTest {

  private static final String[] FORBIDDEN = {
    "org.springframework.jdbc.core.JdbcTemplate",
    "org.springframework.jdbc.core.simple.JdbcClient",
    "com.zaxxer.hikari.HikariDataSource",
    "org.flywaydb.core.Flyway",
    "org.postgresql.Driver",
    "jakarta.persistence.EntityManager",
    "org.hibernate.Session"
  };

  @Test
  void noDatabaseClientIsOnTheClasspath() {
    for (String className : FORBIDDEN) {
      Throwable thrown = catchThrowable(() -> Class.forName(className));
      assertThat(thrown)
          .as("%s must not be resolvable from amqp-fanout", className)
          .isInstanceOf(ClassNotFoundException.class);
    }
  }
}
