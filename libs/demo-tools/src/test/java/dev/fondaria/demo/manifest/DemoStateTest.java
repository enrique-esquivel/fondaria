package dev.fondaria.demo.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoStateTest {

  @TempDir Path tempDir;

  @Test
  void missingFileYieldsInitialStateAtTheGivenDefault() {
    LocalDate today = LocalDate.of(2026, 9, 13);
    DemoState state = DemoState.readFrom(tempDir.resolve("state.json"), today);
    assertThat(state.periodIndex()).isZero();
    assertThat(state.navSeriesIndex()).isZero();
    assertThat(state.currentBusinessDate()).isEqualTo(today);
  }

  @Test
  void roundTripsThroughDisk() {
    Path file = tempDir.resolve("state.json");
    DemoState state = new DemoState(3, 7, LocalDate.of(2026, 10, 15));
    state.writeTo(file);

    DemoState reloaded = DemoState.readFrom(file, LocalDate.now());
    assertThat(reloaded).isEqualTo(state);
  }

  @Test
  void withersProduceIndependentCopies() {
    DemoState original = DemoState.initial(LocalDate.of(2026, 1, 1));
    DemoState advanced =
        original
            .withPeriodIndex(5)
            .withNavSeriesIndex(2)
            .withCurrentBusinessDate(LocalDate.of(2026, 1, 5));

    assertThat(original.periodIndex()).isZero();
    assertThat(advanced.periodIndex()).isEqualTo(5);
    assertThat(advanced.navSeriesIndex()).isEqualTo(2);
    assertThat(advanced.currentBusinessDate()).isEqualTo(LocalDate.of(2026, 1, 5));
  }
}
