package dev.fondaria.valuationapi.health;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import java.lang.reflect.Proxy;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** The {@code demoClock} health component (Task D.1) reports which clock is actually running. */
class DemoClockHealthIndicatorTest {

  @Test
  void reportsDisabledForTheRealClock() {
    Health health = new DemoClockHealthIndicator(StorableClock.systemUTC()).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("enabled", false);
  }

  @Test
  void reportsEnabledAndTheOffsetForASimulatedClock() throws Exception {
    DataSource explodingDataSource =
        (DataSource)
            Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  throw new java.sql.SQLException("unreachable in this test");
                });
    Clock clock = SimulatedClock.wrapping(StorableClock.systemUTC(), explodingDataSource);
    try {
      Health health = new DemoClockHealthIndicator(clock).health();

      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails()).containsEntry("enabled", true);
      assertThat(health.getDetails()).containsEntry("offsetSeconds", 0L);
    } finally {
      ((AutoCloseable) clock).close();
    }
  }
}
