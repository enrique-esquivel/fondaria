package dev.fondaria.statementworker.render;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * How many statements this service has produced — what the Financial Control dashboard shows next
 * to the close that triggered them.
 *
 * <p>Counts only: never a period, a participant or an amount as a tag. A per-period label would be
 * bounded but pointless (one run a month), and anything per-participant is the unbounded
 * cardinality {@code MetricCardinalityTest} exists to refuse. Which period completed, and with how
 * many statements, is {@code workflow.statement_run}'s to say.
 */
@Component
public class StatementMetrics {

  private final Counter rendered;
  private final Counter runsCompleted;

  public StatementMetrics(MeterRegistry registry) {
    Objects.requireNonNull(registry, "registry must not be null");
    this.rendered =
        Counter.builder("statement_rendered_total")
            .description("Participant statements rendered and stored in object storage")
            .register(registry);
    this.runsCompleted =
        Counter.builder("statement_runs_completed_total")
            .description(
                "Monthly statement runs settled COMPLETED: every expected statement stored")
            .register(registry);
  }

  /** One statement written. A redelivered job that rewrites the same object counts again. */
  public void recordRendered() {
    rendered.increment();
  }

  /** One period's run settled {@code COMPLETED}. */
  public void recordRunCompleted() {
    runsCompleted.increment();
  }
}
