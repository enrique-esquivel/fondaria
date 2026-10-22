package dev.fondaria.projection.consume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectionHaltTest {

  @Test
  void startsUntripped() {
    ProjectionHalt halt = new ProjectionHalt();
    assertThat(halt.isHalted()).isFalse();
    assertThat(halt.reason()).isEmpty();
  }

  @Test
  void firstReasonWinsAndTheLatchNeverClears() {
    ProjectionHalt halt = new ProjectionHalt();

    halt.trigger("first: bad schema version at ledger.entries.v1-3 offset 42");
    halt.trigger("second: something else");

    assertThat(halt.isHalted()).isTrue();
    assertThat(halt.reason())
        .contains("first: bad schema version at ledger.entries.v1-3 offset 42");
  }
}
