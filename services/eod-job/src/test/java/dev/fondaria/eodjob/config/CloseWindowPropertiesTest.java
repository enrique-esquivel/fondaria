package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CloseWindowPropertiesTest {

  private static final LocalTime CUTOFF = LocalTime.of(18, 0);

  @Test
  @DisplayName("unset or blank means no window, which is production's behaviour")
  void blankIsNoWindow() {
    assertThat(new CloseWindowProperties(null).requireStart(CUTOFF)).isEmpty();
    assertThat(new CloseWindowProperties("  ").requireStart(CUTOFF)).isEmpty();
  }

  @Test
  @DisplayName("an HH:MM before cut-off is the window's start")
  void validStart() {
    assertThat(new CloseWindowProperties(" 17:00 ").requireStart(CUTOFF))
        .contains(LocalTime.of(17, 0));
  }

  @Test
  @DisplayName("a malformed start fails startup naming the variable")
  void malformedStartFails() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(() -> new CloseWindowProperties("5pm").requireStart(CUTOFF))
        .withMessageContaining(CloseWindowProperties.START_VARIABLE);
  }

  @Test
  @DisplayName("a start at or after cut-off fails startup: the window would never open")
  void startAtCutoffFails() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(() -> new CloseWindowProperties("18:00").requireStart(CUTOFF))
        .withMessageContaining(CloseWindowProperties.START_VARIABLE);
  }
}
