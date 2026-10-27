package dev.fondaria.statementworker.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.statementworker.render.RedisCompletionCounter;
import dev.fondaria.statementworker.render.StatementMetrics;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The settler against fakes: a run completes only once the counter reaches its control total. */
class StatementRunSettlerTest {

  private static final Instant NOW = Instant.parse("2026-10-30T23:30:00Z");

  // Never connected: currentCount is overridden below, and Lettuce connects only on demand.
  private final RedisClient client = RedisClient.create("redis://localhost:1");
  private final Map<String, Long> counts = new HashMap<>();
  private final FakeRuns runs = new FakeRuns();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final StatementRunSettler settler =
      new StatementRunSettler(
          runs,
          new RedisCompletionCounter(client, Duration.ofDays(45), null) {
            @Override
            public OptionalLong currentCount(String period) {
              Long count = counts.get(period);
              return count == null ? OptionalLong.empty() : OptionalLong.of(count);
            }
          },
          Clock.fixed(NOW, ZoneOffset.UTC),
          new StatementMetrics(registry));

  @AfterEach
  void shutdown() {
    client.shutdown();
  }

  private double runsCompleted() {
    return registry.get("statement_runs_completed_total").counter().count();
  }

  @Test
  @DisplayName("a run short of its expected count stays RUNNING and is not counted")
  void shortRunStaysOpen() {
    runs.running("2026-10", 3);
    counts.put("2026-10", 2L);

    settler.sweep();

    assertThat(runs.completed).isEmpty();
    assertThat(runsCompleted()).isZero();
  }

  @Test
  @DisplayName("a run whose counter reaches its expected count settles and is counted once")
  void fullRunSettlesAndIsCounted() {
    runs.running("2026-10", 3);
    counts.put("2026-10", 3L);

    settler.sweep();
    settler.sweep();

    assertThat(runs.completed).containsExactly("2026-10");
    assertThat(runsCompleted()).isEqualTo(1.0);
  }

  private static final class FakeRuns implements StatementRunRepository {

    private final Map<String, StatementRun> rows = new HashMap<>();
    private final List<String> completed = new ArrayList<>();

    void running(String period, long expected) {
      rows.put(
          period,
          new StatementRun(
              period,
              LocalDate.of(2026, 10, 30),
              StatementRunStatus.RUNNING,
              NOW.minusSeconds(60),
              Optional.empty(),
              Optional.empty(),
              expected,
              Optional.empty(),
              Optional.empty()));
    }

    @Override
    public void openIfAbsent(
        String period, LocalDate closeBusinessDate, Instant startedAt, long expectedCount) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<StatementRun> findRunning() {
      return rows.values().stream()
          .filter(run -> run.status() == StatementRunStatus.RUNNING)
          .toList();
    }

    @Override
    public void complete(String period, Instant completedAt, long durationMillis, long count) {
      StatementRun run = rows.get(period);
      rows.put(
          period,
          new StatementRun(
              period,
              run.closeBusinessDate(),
              StatementRunStatus.COMPLETED,
              run.startedAt(),
              Optional.of(completedAt),
              Optional.of(durationMillis),
              run.expectedCount(),
              Optional.of(count),
              Optional.empty()));
      completed.add(period);
    }

    @Override
    public Optional<StatementRun> find(String period) {
      return Optional.ofNullable(rows.get(period));
    }
  }
}
