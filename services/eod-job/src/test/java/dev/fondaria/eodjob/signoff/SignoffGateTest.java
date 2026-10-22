package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The final gate: no {@code workflow.close_signoff} row, no downstream. It fails closed the one way
 * it can — sign-off is not a lifecycle, so "not signed off" is the only closed state and it is the
 * default.
 */
class SignoffGateTest {

  private static final LocalDate DATE = LocalDate.of(2026, 8, 31);

  private final SignoffTestDoubles.Signoffs signoffs = new SignoffTestDoubles.Signoffs();
  private final SignoffGate gate = new SignoffGate(signoffs);

  private static CloseSignoff row(LocalDate date) {
    return new CloseSignoff(
        date,
        Instant.parse("2026-09-01T02:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        100L,
        "snapshots/dt=" + date + "/positions.ndjson.gz",
        "snapshots/dt=" + date + "/manifest.json",
        Optional.empty());
  }

  @Test
  @DisplayName("a date with no sign-off row is blocked")
  void noRowBlocks() {
    assertThat(gate.isSignedOff(DATE)).isFalse();
    assertThatThrownBy(() -> gate.requireSignedOff(DATE))
        .isInstanceOf(CloseNotSignedOffException.class)
        .hasMessageContaining("has not been signed off");
  }

  @Test
  @DisplayName("a date with a sign-off row opens the gate")
  void aRowOpens() {
    signoffs.put(row(DATE));

    assertThat(gate.isSignedOff(DATE)).isTrue();
    assertThatCode(() -> gate.requireSignedOff(DATE)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a sign-off for one date does not open a different date")
  void scopedToTheDate() {
    signoffs.put(row(DATE));

    assertThat(gate.isSignedOff(DATE.plusDays(1))).isFalse();
  }
}
