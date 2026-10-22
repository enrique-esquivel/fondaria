package dev.fondaria.contribution.staff;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BreakGlassMetrics {

  private final Counter breakGlassActionsTotal;
  private final StaffActionRepository repository;
  private final java.time.Clock clock;

  public BreakGlassMetrics(
      MeterRegistry registry, StaffActionRepository repository, java.time.Clock clock) {
    this.repository = java.util.Objects.requireNonNull(repository, "repository");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
    this.breakGlassActionsTotal =
        Counter.builder("break_glass_actions_total")
            .description("Total number of break-glass emergency actions triggered")
            .register(registry);

    Gauge.builder("break_glass_active_count", this, BreakGlassMetrics::getActiveCount)
        .description("Number of currently active/pending break-glass emergency actions")
        .register(registry);
  }

  public void recordBreakGlassTriggered() {
    breakGlassActionsTotal.increment();
  }

  public int getActiveCount() {
    return repository.countActiveBreakGlassActions(clock.instant());
  }
}
