package dev.fondaria.allotmentjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.fondaria.common.SimulatedClock;
import dev.fondaria.common.StorableClock;
import java.lang.reflect.Proxy;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AllotmentJobApplication#clock} without a Spring context — the two branches Task D.1 adds
 * ({@code phase-D-demo.md}: "with the variable unset, ... {@code reference.demo_clock} is never
 * read (assert with a test double)").
 */
class AllotmentJobApplicationClockTest {

  @Test
  @DisplayName(
      "DEMO_CLOCK_ENABLED unset: a StorableClock is built and the DataSource is never touched")
  void disabledNeverTouchesDataSource() {
    DataSource explodingIfTouched = explodingDataSource();

    Clock clock = new AllotmentJobApplication().clock(explodingIfTouched, false);

    // Not just "not a SimulatedClock": exactly the real clock, so a broken third clock type
    // could not slip through this assertion either. StorableClock#equals compares delegates.
    assertThat(clock).isEqualTo(StorableClock.systemUTC());
  }

  @Test
  @DisplayName("DEMO_CLOCK_ENABLED=true builds a SimulatedClock")
  void enabledBuildsSimulatedClock() throws Exception {
    DataSource dataSource =
        explodingDataSource(); // failures are swallowed by SimulatedClock's poll

    Clock clock = new AllotmentJobApplication().clock(dataSource, true);
    try {
      assertThat(clock).isInstanceOf(SimulatedClock.class);
      assertThatCode(clock::instant).doesNotThrowAnyException();
    } finally {
      ((AutoCloseable) clock).close();
    }
  }

  private static DataSource explodingDataSource() {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              throw new java.sql.SQLException(
                  "DataSource must not be reached: " + method.getName());
            });
  }
}
