package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

  private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
  private static final LocalTime CUTOFF = LocalTime.of(15, 0);

  @Test
  void oneNanosecondEitherSideOfCutoffFallsOnDifferentBusinessDates() {
    BusinessCalendar calendar = new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of());
    // 2026-08-19 is a Wednesday, 2026-08-20 a Thursday — both plain business days.
    Instant justBeforeCutoff =
        ZonedDateTime.of(
                LocalDate.of(2026, 8, 19), LocalTime.of(14, 59, 59, 999_999_999), MEXICO_CITY)
            .toInstant();
    Instant atCutoff = justBeforeCutoff.plusNanos(1);
    assertThat(atCutoff)
        .isEqualTo(ZonedDateTime.of(LocalDate.of(2026, 8, 19), CUTOFF, MEXICO_CITY).toInstant());

    assertThat(calendar.businessDateOf(justBeforeCutoff)).isEqualTo(LocalDate.of(2026, 8, 19));
    assertThat(calendar.businessDateOf(atCutoff)).isEqualTo(LocalDate.of(2026, 8, 20));
  }

  @Test
  void aMarketHolidayIsNotAValuationDateAndTheNextValuationDateIsCorrect() {
    // 2026-08-20 (Thursday) declared a holiday; 2026-08-21 (Friday) is the next business day.
    LocalDate holiday = LocalDate.of(2026, 8, 20);
    BusinessCalendar calendar = new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of(holiday));

    assertThat(calendar.isBusinessDate(holiday)).isFalse();
    assertThat(calendar.nextBusinessDate(LocalDate.of(2026, 8, 19)))
        .isEqualTo(LocalDate.of(2026, 8, 21));

    // A timestamp that rolls onto the holiday (at-or-after cutoff on the day before) lands on the
    // next real business date instead.
    Instant atCutoffDayBefore =
        ZonedDateTime.of(LocalDate.of(2026, 8, 19), CUTOFF, MEXICO_CITY).toInstant();
    assertThat(calendar.businessDateOf(atCutoffDayBefore)).isEqualTo(LocalDate.of(2026, 8, 21));
  }

  @Test
  void aWeekendIsNotABusinessDateAndRollsToMonday() {
    BusinessCalendar calendar = new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of());
    LocalDate saturday = LocalDate.of(2026, 8, 22);
    LocalDate sunday = LocalDate.of(2026, 8, 23);
    LocalDate friday = LocalDate.of(2026, 8, 21);
    LocalDate nextMonday = LocalDate.of(2026, 8, 24);

    assertThat(calendar.isBusinessDate(saturday)).isFalse();
    assertThat(calendar.isBusinessDate(sunday)).isFalse();
    assertThat(calendar.nextBusinessDate(friday)).isEqualTo(nextMonday);
  }

  @Test
  void businessDateOfIsMonotonicAcrossTheSpringForwardTransition() {
    assertMonotonicAcrossTransition(findTransition(true));
  }

  @Test
  void businessDateOfIsMonotonicAcrossTheFallBackTransition() {
    assertMonotonicAcrossTransition(findTransition(false));
  }

  /**
   * Walks a real historical DST transition of America/Mexico_City hour by hour and asserts
   * business-date computation never throws and never regresses — the property a naive
   * LocalDateTime-based implementation (ambiguous on overlap, invalid on gap) would violate.
   */
  private void assertMonotonicAcrossTransition(ZoneOffsetTransition transition) {
    BusinessCalendar calendar = new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of());
    Instant start = transition.getInstant().minus(Duration.ofDays(2));
    Instant end = transition.getInstant().plus(Duration.ofDays(2));

    LocalDate previous = null;
    for (Instant t = start; t.isBefore(end); t = t.plus(Duration.ofHours(1))) {
      LocalDate businessDate = calendar.businessDateOf(t);
      if (previous != null) {
        assertThat(businessDate).isAfterOrEqualTo(previous);
      }
      previous = businessDate;
    }
  }

  private static ZoneOffsetTransition findTransition(boolean gap) {
    ZoneRules rules = MEXICO_CITY.getRules();
    List<ZoneOffsetTransition> transitions = rules.getTransitions();
    return transitions.stream()
        .filter(t -> gap ? t.isGap() : t.isOverlap())
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "America/Mexico_City has no historical "
                        + (gap ? "gap" : "overlap")
                        + " transition to test against"));
  }

  @Test
  void rejectsNullArguments() {
    BusinessCalendar calendar = new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of());
    assertThatThrownBy(() -> new BusinessCalendar(null, CUTOFF, Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new BusinessCalendar(MEXICO_CITY, null, Set.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new BusinessCalendar(MEXICO_CITY, CUTOFF, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> calendar.businessDateOf(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> calendar.isBusinessDate(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> calendar.nextBusinessDate(null))
        .isInstanceOf(NullPointerException.class);
  }
}
