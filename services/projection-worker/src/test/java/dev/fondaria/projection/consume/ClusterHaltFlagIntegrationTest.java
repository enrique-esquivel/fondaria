package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.projection.config.CheckpointProperties;
import dev.fondaria.projection.config.HaltProperties;
import dev.fondaria.testing.PostgresFixture;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link ClusterHaltFlag} against a real PostgreSQL (row 26f, ADR-0032 §2).
 *
 * <p>The fixture is reused across runs, so every test uses a fresh {@code projection_name} and
 * asserts only over its own rows: a halt left active under a shared name would halt every later
 * run's worker.
 */
@Tag("integration")
class ClusterHaltFlagIntegrationTest {

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void bootPostgres() {
    PostgresFixture pg = PostgresFixture.getInstance();
    dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    jdbc = new JdbcTemplate(dataSource);
  }

  private static String freshName() {
    return "halt-test-" + UUID.randomUUID();
  }

  private static ClusterHaltFlag flagFor(String projectionName, String instanceId) {
    return new ClusterHaltFlag(
        dataSource,
        new HaltProperties(projectionName, null, null),
        new CheckpointProperties(instanceId, null, null));
  }

  private static void clear(String projectionName) {
    jdbc.update(
        "UPDATE workflow.projection_halt SET cleared_at = now(), cleared_by = 'test',"
            + " clear_note = 'test cleanup' WHERE projection_name = ? AND cleared_at IS NULL",
        projectionName);
  }

  @Test
  @DisplayName("no halt is active until one is raised")
  void nothingActiveToBeginWith() {
    assertThat(flagFor(freshName(), "pod-a").active()).isEmpty();
  }

  @Test
  @DisplayName("raise records who, why and where, and active() returns it")
  void raiseThenActive() {
    String name = freshName();
    try {
      assertThat(flagFor(name, "pod-a").raise("MALFORMED_EVENT", "projection.entries.v1.3"))
          .isTrue();

      // Another replica sees it.
      var active = flagFor(name, "pod-b").active().orElseThrow();
      assertThat(active.haltedBy()).isEqualTo("pod-a");
      assertThat(active.reasonCode()).isEqualTo("MALFORMED_EVENT");
      assertThat(active.sourceQueue()).isEqualTo("projection.entries.v1.3");
      assertThat(active.haltId()).isPositive();
    } finally {
      clear(name);
    }
  }

  @Test
  @DisplayName("a second replica meeting the same poison leaves the first halt standing")
  void raiseIsIdempotentWhileActive() {
    String name = freshName();
    try {
      assertThat(flagFor(name, "pod-a").raise("MALFORMED_EVENT", "q1")).isTrue();
      assertThat(flagFor(name, "pod-b").raise("UNKNOWN_SCHEMA_VERSION", "q2")).isFalse();

      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM workflow.projection_halt WHERE projection_name = ?",
                  Long.class,
                  name))
          .isEqualTo(1L);
      assertThat(flagFor(name, "pod-a").active().orElseThrow().haltedBy()).isEqualTo("pod-a");
    } finally {
      clear(name);
    }
  }

  @Test
  @DisplayName("clearing ends the halt but keeps the row; a later poison raises a new one")
  void clearedHaltIsInactiveAndRaisableAgain() {
    String name = freshName();
    try {
      ClusterHaltFlag flag = flagFor(name, "pod-a");
      flag.raise("MALFORMED_EVENT", "q");

      clear(name);

      assertThat(flag.active()).isEmpty();
      assertThat(flag.raise("MALFORMED_EVENT", "q")).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM workflow.projection_halt WHERE projection_name = ?",
                  Long.class,
                  name))
          .isEqualTo(2L);
    } finally {
      clear(name);
    }
  }

  @Test
  @DisplayName("one projection's halt is invisible to another")
  void haltsAreScopedByProjectionName() {
    String a = freshName();
    String b = freshName();
    try {
      flagFor(a, "pod-a").raise("MALFORMED_EVENT", "q");

      assertThat(flagFor(b, "pod-b").active()).isEmpty();
    } finally {
      clear(a);
    }
  }

  @Test
  @DisplayName("a halt cannot reach the database with an unknown reason code")
  void reasonCodeIsConstrained() {
    assertThatThrownBy(() -> flagFor(freshName(), "pod-a").raise("SOMETHING_ELSE", "q"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("clearing is one act: when, who and why arrive together")
  void clearMustRecordWhoAndWhy() {
    String name = freshName();
    try {
      flagFor(name, "pod-a").raise("MALFORMED_EVENT", "q");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE workflow.projection_halt SET cleared_at = now()"
                          + " WHERE projection_name = ? AND cleared_at IS NULL",
                      name))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE workflow.projection_halt SET cleared_at = now(), cleared_by = 'op',"
                          + " clear_note = '  ' WHERE projection_name = ? AND cleared_at IS NULL",
                      name))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(flagFor(name, "pod-a").active()).isPresent();
    } finally {
      clear(name);
    }
  }
}
