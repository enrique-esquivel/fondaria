package dev.fondaria.balanceapi.health;

import dev.fondaria.common.SimulatedClock;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Reports whether this instance is running the demo's simulated clock and, if so, the offset it
 * last polled (Task D.1, {@code phase-D-demo.md}).
 *
 * <p><b>Always {@code UP}.</b> This is a demo dial, not a reliability signal, and is not a member
 * of any health group. Its purpose is what {@code demo-tools advance} (Task D.2) reads: since each
 * service polls {@code reference.demo_clock} independently, {@code advance} needs a way to confirm
 * every instance has actually picked up a new offset before the next beat runs, rather than
 * assuming a fixed number of poll intervals have elapsed.
 */
public class DemoClockHealthIndicator implements HealthIndicator {

  private final Clock clock;

  public DemoClockHealthIndicator(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public Health health() {
    if (clock instanceof SimulatedClock simulated) {
      return Health.up()
          .withDetail("enabled", true)
          .withDetail("offsetSeconds", simulated.offsetSeconds())
          .build();
    }
    return Health.up().withDetail("enabled", false).build();
  }
}
