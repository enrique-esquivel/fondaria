package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link JdbcProjectionProgress} against the real {@code projection.projection_consumer_checkpoint}
 * ({@code V35}). Each test reads only rows under a projection name it made up, so whatever other
 * suites left in the shared fixture never enters the aggregate (docs/ENGINEERING-RULES.md §4).
 */
@SpringBootTest
class JdbcProjectionProgressIntegrationTest extends ServiceIntegrationTest {

  private static final long HEAD = 1_788_559_800_000_000_000L;
  private static final Duration QUIET = Duration.ofSeconds(15);

  @Autowired private JdbcClient jdbc;

  private String name;
  private JdbcProjectionProgress progress;

  @BeforeEach
  void freshProjectionName() {
    name = "test-" + UUID.randomUUID();
    progress = new JdbcProjectionProgress(jdbc, name);
  }

  @Test
  @DisplayName("no rows: nothing committed and no idle time")
  void noRows() {
    ProjectionProgress.Snapshot snapshot = progress.against(HEAD, QUIET);

    assertThat(snapshot.highestCommittedNanos()).isEmpty();
    assertThat(snapshot.busyConsumersBehind()).isZero();
    assertThat(snapshot.sinceLastAdvance()).isEmpty();
  }

  @Test
  @DisplayName(
      "the highest commit is exact to the nanosecond, and only recent consumers behind the head"
          + " count as draining")
  void readsHighestAndBusyBehind() {
    consumer("a", HEAD, Duration.ZERO); // at the head
    consumer("b", HEAD - 5_000_000_000L, Duration.ofSeconds(1)); // behind, still draining
    consumer("c", HEAD - 9_000_000_000L, Duration.ofMinutes(10)); // behind, long quiet: drained

    ProjectionProgress.Snapshot snapshot = progress.against(HEAD, QUIET);

    assertThat(snapshot.highestCommittedNanos()).contains(HEAD);
    assertThat(snapshot.busyConsumersBehind()).isEqualTo(1);
    assertThat(snapshot.sinceLastAdvance().orElseThrow()).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("idle time is measured from the most recent advance of any consumer")
  void idleSinceTheLastAdvance() {
    consumer("a", HEAD - 1L, Duration.ofMinutes(3));
    consumer("b", HEAD - 2L, Duration.ofMinutes(7));

    ProjectionProgress.Snapshot snapshot = progress.against(HEAD, QUIET);

    assertThat(snapshot.busyConsumersBehind()).isZero();
    assertThat(snapshot.sinceLastAdvance().orElseThrow())
        .isBetween(Duration.ofMinutes(3), Duration.ofMinutes(3).plusSeconds(30));
  }

  private void consumer(String id, long lastNanos, Duration idle) {
    jdbc.sql(
            """
            INSERT INTO projection.projection_consumer_checkpoint
              (projection_name, consumer_id, queue_name, last_ledger_timestamp, updated_at)
            VALUES (:name, :id, 'projection.test', :last, now() - :idleMillis * interval '1 ms')
            """)
        .param("name", name)
        .param("id", id)
        .param("last", lastNanos)
        .param("idleMillis", idle.toMillis())
        .update();
  }
}
