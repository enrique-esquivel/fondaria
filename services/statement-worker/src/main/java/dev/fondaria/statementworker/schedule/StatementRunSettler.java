package dev.fondaria.statementworker.schedule;

import dev.fondaria.statementworker.render.RedisCompletionCounter;
import java.time.Clock;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles a {@code workflow.statement_run} row {@code COMPLETED} once the Redis completion counter
 * reaches its {@code expected_count} (Task 7.5b, {@code ADR-0022} §3: "the final counter value
 * copied back when the run settles").
 *
 * <p>Polling rather than event-driven: nothing publishes "a statement was rendered" as a message a
 * settler could subscribe to — the render path's only externally visible side effects are the
 * object write and the counter increment, and turning the counter into a second event stream just
 * to trigger this comparison would be the platform's one control (Redis, ADR-0022 §3) growing a
 * second channel for the sole purpose of watching itself. A short interval costs one cheap {@code
 * GET} per {@code RUNNING} period — there is at most one of those at a time in practice — until it
 * settles.
 */
@Component
public class StatementRunSettler {

  private static final Logger LOGGER = LoggerFactory.getLogger(StatementRunSettler.class);

  private final StatementRunRepository runs;
  private final RedisCompletionCounter counter;
  private final Clock clock;

  public StatementRunSettler(
      StatementRunRepository runs, RedisCompletionCounter counter, Clock clock) {
    this.runs = runs;
    this.counter = counter;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fondaria.statement.settlement-sweep-interval:5s}")
  public void sweep() {
    for (StatementRun run : runs.findRunning()) {
      OptionalLong current = counter.currentCount(run.period());
      if (current.isEmpty()) {
        continue;
      }
      if (current.getAsLong() >= run.expectedCount()) {
        long durationMillis = clock.instant().toEpochMilli() - run.startedAt().toEpochMilli();
        runs.complete(
            run.period(), clock.instant(), Math.max(durationMillis, 0), current.getAsLong());
        LOGGER.info(
            "statement run settled for period {}: {} / {} statements",
            run.period(),
            current.getAsLong(),
            run.expectedCount());
      }
    }
  }
}
