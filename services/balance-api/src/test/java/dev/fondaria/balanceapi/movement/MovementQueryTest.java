package dev.fondaria.balanceapi.movement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.ParticipantId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovementQueryTest {

  private static MovementQuery with(int limit) {
    return query(Optional.empty(), Optional.empty(), limit);
  }

  private static MovementQuery query(Optional<LocalDate> from, Optional<LocalDate> to, int limit) {
    return new MovementQuery(
        ParticipantId.of(UUID.randomUUID()),
        from,
        to,
        Optional.empty(),
        Optional.empty(),
        limit,
        Optional.empty());
  }

  @Test
  @DisplayName("the page size is capped at 200 and never zero or negative")
  void thePageSizeIsBounded() {
    assertThatCode(() -> with(1)).doesNotThrowAnyException();
    assertThatCode(() -> with(MovementQuery.MAXIMUM_LIMIT)).doesNotThrowAnyException();
    assertThatThrownBy(() -> with(0)).isInstanceOf(InvalidMovementQueryException.class);
    assertThatThrownBy(() -> with(-1)).isInstanceOf(InvalidMovementQueryException.class);
    assertThatThrownBy(() -> with(MovementQuery.MAXIMUM_LIMIT + 1))
        .isInstanceOf(InvalidMovementQueryException.class);
  }

  @Test
  @DisplayName("an inverted date range is refused rather than silently returning nothing")
  void anInvertedRangeIsRefused() {
    assertThatThrownBy(
            () ->
                query(
                    Optional.of(LocalDate.of(2026, 9, 1)),
                    Optional.of(LocalDate.of(2026, 8, 1)),
                    50))
        .isInstanceOf(InvalidMovementQueryException.class);

    assertThatCode(
            () ->
                query(
                    Optional.of(LocalDate.of(2026, 8, 1)),
                    Optional.of(LocalDate.of(2026, 8, 1)),
                    50))
        .as("a single day is a legal range")
        .doesNotThrowAnyException();
  }
}
