package dev.fondaria.projection.health;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.projection.consume.ProjectionHalt;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class ProjectionHaltHealthIndicatorTest {

  @Test
  void upWhileRunning() {
    ProjectionHaltHealthIndicator indicator =
        new ProjectionHaltHealthIndicator(new ProjectionHalt());
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void downOnceHalted() {
    ProjectionHalt halt = new ProjectionHalt();
    halt.trigger("poison at test.entries-0 offset 7");

    ProjectionHaltHealthIndicator indicator = new ProjectionHaltHealthIndicator(halt);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).containsKey("reason");
  }

  @Test
  void downUntilTheStartupGateOpens() {
    boolean[] open = {false};
    ProjectionHaltHealthIndicator indicator =
        new ProjectionHaltHealthIndicator(new ProjectionHalt(), () -> open[0]);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

    open[0] = true;
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }
}
