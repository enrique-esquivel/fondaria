package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StorableClockTest {

  /** A nanosecond-resolution instant, as a Linux host reports one. */
  private static final Instant WITH_NANOS = Instant.parse("2026-09-02T02:03:48.295553188Z");

  private static final Instant TRUNCATED = Instant.parse("2026-09-02T02:03:48.295553Z");

  @Test
  @DisplayName("sub-microsecond digits are dropped, never rounded")
  void subMicrosecondDigitsAreDropped() {
    Clock clock = StorableClock.wrapping(Clock.fixed(WITH_NANOS, ZoneOffset.UTC));

    assertThat(clock.instant()).isEqualTo(TRUNCATED);
    assertThat(clock.instant().getNano() % 1_000).isZero();
  }

  /**
   * Truncated, not rounded. Rounding could carry an instant <i>forward</i> past a boundary, which
   * would let a recorded timestamp claim a moment that had not happened when it was minted.
   */
  @Test
  @DisplayName("an instant is never moved forward")
  void anInstantIsNeverMovedForward() {
    Instant justUnder = Instant.parse("2026-09-02T02:03:48.295553999Z");
    Clock clock = StorableClock.wrapping(Clock.fixed(justUnder, ZoneOffset.UTC));

    assertThat(clock.instant()).isEqualTo(TRUNCATED);
    assertThat(clock.instant()).isBeforeOrEqualTo(justUnder);
  }

  /**
   * The property the whole class exists for, asserted against the real host clock rather than a
   * fixture — this is the assertion that fails on Linux if the wrapping is ever removed, and it
   * costs nothing to run everywhere.
   */
  @Test
  @DisplayName("the system clock never yields precision PostgreSQL cannot store")
  void theSystemClockNeverYieldsUnstorablePrecision() {
    Clock clock = StorableClock.systemUTC();

    for (int i = 0; i < 10_000; i++) {
      Instant now = clock.instant();
      assertThat(now.getNano() % 1_000)
          .as("sub-microsecond digits survive a timestamptz round trip nowhere")
          .isZero();
    }
  }

  @Test
  @DisplayName("an already-storable instant is untouched")
  void anAlreadyStorableInstantIsUntouched() {
    Clock clock = StorableClock.wrapping(Clock.fixed(TRUNCATED, ZoneOffset.UTC));

    assertThat(clock.instant()).isEqualTo(TRUNCATED);
  }

  @Test
  @DisplayName("wrapping is idempotent rather than nested")
  void wrappingIsIdempotent() {
    Clock once = StorableClock.systemUTC();

    assertThat(StorableClock.wrapping(once)).isSameAs(once);
  }

  @Test
  @DisplayName("the zone is the delegate's, and a rezoned clock still truncates")
  void theZoneIsPreservedAndRezoningKeepsTheGuarantee() {
    ZoneId mexicoCity = ZoneId.of("America/Mexico_City");
    Clock clock = StorableClock.wrapping(Clock.fixed(WITH_NANOS, ZoneOffset.UTC));

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);

    Clock rezoned = clock.withZone(mexicoCity);
    assertThat(rezoned.getZone()).isEqualTo(mexicoCity);
    assertThat(rezoned.instant()).isEqualTo(TRUNCATED);
  }

  @Test
  @DisplayName("millis are unaffected — they were already coarser than a microsecond")
  void millisAreUnaffected() {
    Clock clock = StorableClock.wrapping(Clock.fixed(WITH_NANOS, ZoneOffset.UTC));

    assertThat(clock.millis()).isEqualTo(WITH_NANOS.toEpochMilli());
  }

  @Test
  @DisplayName("two clocks over the same delegate are equal")
  void equality() {
    Clock delegate = Clock.fixed(WITH_NANOS, ZoneOffset.UTC);

    assertThat(StorableClock.wrapping(delegate)).isEqualTo(StorableClock.wrapping(delegate));
    assertThat(StorableClock.wrapping(delegate))
        .isNotEqualTo(StorableClock.wrapping(Clock.fixed(TRUNCATED, ZoneOffset.UTC)));
  }

  @Test
  @DisplayName("a null delegate is refused rather than wrapped")
  void aNullDelegateIsRefused() {
    assertThatThrownBy(() -> StorableClock.wrapping(null)).isInstanceOf(NullPointerException.class);
  }
}
