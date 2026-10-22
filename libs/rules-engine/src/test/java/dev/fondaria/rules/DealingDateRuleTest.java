package dev.fondaria.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Task 21.1's acceptance criteria, at the level of the rule itself.
 *
 * <p>Fixed dates throughout, all in 2026 and all checked against a real weekday: a test that
 * computed its own expectations from the same calendar the rule uses would pass on a broken
 * calendar. Mexico City, an 18:00 cut-off — the platform's configured defaults.
 */
class DealingDateRuleTest {

  private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");
  private static final LocalTime CUTOFF = LocalTime.of(18, 0);

  /** Wednesday 2026-03-11. */
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 3, 11);

  private static BusinessCalendar calendar(LocalDate... holidays) {
    return new BusinessCalendar(MEXICO_CITY, CUTOFF, Set.of(holidays));
  }

  /** An instant well before cut-off on {@code date}, so the batch's own value date governs. */
  private static Instant morningOf(LocalDate date) {
    return date.atTime(9, 0).atZone(MEXICO_CITY).toInstant();
  }

  @Nested
  @DisplayName("cut-off")
  class CutOff {

    /**
     * Acceptance criterion 1. The value date is old enough that the plan's lag can never be the
     * binding constraint, so the only thing separating these two batches is one second of wall
     * clock — which is exactly the claim being tested.
     */
    @Test
    @DisplayName("a batch one second either side of cut-off deals on different valuation dates")
    void oneSecondEitherSideOfCutOffDealsOnDifferentDates() {
      BusinessCalendar calendar = calendar();
      LocalDate longPast = LocalDate.of(2026, 1, 5);

      Instant justBefore = WEDNESDAY.atTime(17, 59, 59).atZone(MEXICO_CITY).toInstant();
      Instant atCutOff = WEDNESDAY.atTime(18, 0, 0).atZone(MEXICO_CITY).toInstant();

      LocalDate before = DealingDateRule.dealingDateFor(longPast, justBefore, 0, calendar);
      LocalDate after = DealingDateRule.dealingDateFor(longPast, atCutOff, 0, calendar);

      assertThat(before).isEqualTo(WEDNESDAY);
      assertThat(after).isEqualTo(LocalDate.of(2026, 3, 12));
      assertThat(before).isNotEqualTo(after);
    }

    /** A batch that misses cut-off deals at the next valuation date — never dropped. */
    @Test
    @DisplayName("a batch staged after cut-off on a Friday deals on the Monday")
    void afterCutOffOnFridayDealsOnMonday() {
      LocalDate friday = LocalDate.of(2026, 3, 13);
      Instant evening = friday.atTime(20, 30).atZone(MEXICO_CITY).toInstant();

      assertThat(DealingDateRule.dealingDateFor(LocalDate.of(2026, 1, 5), evening, 0, calendar()))
          .isEqualTo(LocalDate.of(2026, 3, 16));
    }

    /**
     * Never back-dated. The value date is two months old — the money was late arriving, not early —
     * and the batch still deals on the day it became dealable rather than at a price struck in
     * January, which would allot units at a NAV chosen with information nobody had at the time.
     */
    @Test
    @DisplayName("a stale value date is never back-dated to a price already struck")
    void staleValueDateIsNeverBackDated() {
      assertThat(
              DealingDateRule.dealingDateFor(
                  LocalDate.of(2026, 1, 5), morningOf(WEDNESDAY), 0, calendar()))
          .isEqualTo(WEDNESDAY);
    }
  }

  @Nested
  @DisplayName("the dealing calendar")
  class DealingCalendar {

    /**
     * Acceptance criterion 2. 2026-03-16 is a Monday; make it a holiday and it rolls to Tuesday.
     */
    @Test
    @DisplayName("a value date on a market holiday deals on the next valuation date")
    void holidayValueDateDealsOnTheNextValuationDate() {
      LocalDate holiday = LocalDate.of(2026, 3, 16);
      BusinessCalendar calendar = calendar(holiday);

      assertThat(DealingDateRule.dealingDateFor(holiday, morningOf(holiday), 0, calendar))
          .isEqualTo(LocalDate.of(2026, 3, 17));
    }

    @Test
    @DisplayName("a value date on a Saturday deals on the Monday")
    void weekendValueDateDealsOnTheMonday() {
      LocalDate saturday = LocalDate.of(2026, 3, 14);

      assertThat(
              DealingDateRule.dealingDateFor(
                  saturday, morningOf(LocalDate.of(2026, 3, 2)), 0, calendar()))
          .isEqualTo(LocalDate.of(2026, 3, 16));
    }

    /**
     * A zero lag must not shift an ordinary weekday. {@code BusinessCalendar.nextBusinessDate} is
     * strictly-after by contract, so a rule that called it unconditionally would quietly deal every
     * batch a day late — the bug this asserts against.
     */
    @Test
    @DisplayName("a zero lag on a business day deals that same day")
    void zeroLagOnABusinessDayDealsSameDay() {
      assertThat(
              DealingDateRule.dealingDateFor(
                  WEDNESDAY, morningOf(LocalDate.of(2026, 3, 2)), 0, calendar()))
          .isEqualTo(WEDNESDAY);
    }
  }

  @Nested
  @DisplayName("the plan's dealing lag")
  class DealingLag {

    @Test
    @DisplayName("the lag is counted in business days, not calendar days")
    void lagIsCountedInBusinessDays() {
      // Wednesday + 3 business days = Monday 2026-03-16, skipping the weekend.
      assertThat(
              DealingDateRule.dealingDateFor(
                  WEDNESDAY, morningOf(LocalDate.of(2026, 3, 2)), 3, calendar()))
          .isEqualTo(LocalDate.of(2026, 3, 16));
    }

    /**
     * The lag counts business days, so a holiday inside the span is skipped rather than consumed.
     */
    @Test
    @DisplayName("a holiday inside the lag span does not count against it")
    void holidayInsideTheLagDoesNotCountAgainstIt() {
      // Thursday 2026-03-12 shut. Wednesday + 2 business days is therefore Friday the
      // 13th and then Monday the 16th, not the 12th and the 13th.
      BusinessCalendar withHoliday = calendar(LocalDate.of(2026, 3, 12));

      assertThat(
              DealingDateRule.dealingDateFor(
                  WEDNESDAY, morningOf(LocalDate.of(2026, 3, 2)), 2, withHoliday))
          .isEqualTo(LocalDate.of(2026, 3, 16));
    }

    /**
     * The lag rolls the value date forward first, then advances. Rolling after advancing would
     * count the holiday as one of the lag's days and deal a day early.
     */
    @Test
    @DisplayName("the value date is rolled to a business date before the lag is applied")
    void valueDateIsRolledBeforeTheLagIsApplied() {
      LocalDate saturday = LocalDate.of(2026, 3, 14);

      assertThat(
              DealingDateRule.dealingDateFor(
                  saturday, morningOf(LocalDate.of(2026, 3, 2)), 1, calendar()))
          .isEqualTo(LocalDate.of(2026, 3, 17));
    }

    /** Cut-off is a floor, not an override: an early batch still waits out its plan's lag. */
    @Test
    @DisplayName("a lag later than cut-off's date wins")
    void lagLaterThanCutOffWins() {
      assertThat(DealingDateRule.dealingDateFor(WEDNESDAY, morningOf(WEDNESDAY), 5, calendar()))
          .isEqualTo(LocalDate.of(2026, 3, 18));
    }
  }

  @Nested
  @DisplayName("arguments")
  class Arguments {

    @Test
    void nullArgumentsAreRejected() {
      BusinessCalendar calendar = calendar();
      Instant readyAt = morningOf(WEDNESDAY);

      assertThatThrownBy(() -> DealingDateRule.dealingDateFor(null, readyAt, 0, calendar))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> DealingDateRule.dealingDateFor(WEDNESDAY, null, 0, calendar))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> DealingDateRule.dealingDateFor(WEDNESDAY, readyAt, 0, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a negative lag is rejected rather than silently back-dating")
    void negativeLagIsRejected() {
      BusinessCalendar calendar = calendar();
      Instant readyAt = morningOf(WEDNESDAY);

      assertThatThrownBy(() -> DealingDateRule.dealingDateFor(WEDNESDAY, readyAt, -1, calendar))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dealingLagBusinessDays");
    }

    /** An unbounded lag would turn a status read into a spin over the calendar. */
    @Test
    @DisplayName("a lag beyond the maximum is rejected rather than looped over")
    void lagBeyondTheMaximumIsRejected() {
      BusinessCalendar calendar = calendar();
      Instant readyAt = morningOf(WEDNESDAY);
      int tooLarge = DealingDateRule.MAXIMUM_DEALING_LAG_BUSINESS_DAYS + 1;

      assertThatThrownBy(
              () -> DealingDateRule.dealingDateFor(WEDNESDAY, readyAt, tooLarge, calendar))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dealingLagBusinessDays");
    }
  }

  /**
   * The same inputs answer the same date however much later they are asked. This is the property
   * {@code LocalDate.now()} would destroy, stated as behaviour rather than as a grep — the grep
   * ({@code NoAmbientClockInRulesTest}) catches the source, this catches the consequence.
   */
  @Test
  @DisplayName("the answer does not depend on when it is asked")
  void theAnswerDoesNotDependOnWhenItIsAsked() {
    BusinessCalendar calendar = calendar();
    Instant stagedAt = morningOf(WEDNESDAY);

    LocalDate first = DealingDateRule.dealingDateFor(WEDNESDAY, stagedAt, 2, calendar);
    LocalDate later = DealingDateRule.dealingDateFor(WEDNESDAY, stagedAt, 2, calendar);

    assertThat(first).isEqualTo(later).isEqualTo(LocalDate.of(2026, 3, 13));
  }

  /**
   * Whatever the inputs, the result is a date the market is open on — nothing else can be priced.
   */
  @Test
  @DisplayName("the result is always a business date")
  void theResultIsAlwaysABusinessDate() {
    BusinessCalendar calendar = calendar(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 17));

    for (int day = 1; day <= 28; day++) {
      LocalDate valueDate = LocalDate.of(2026, 3, day);
      for (int lag = 0; lag <= 5; lag++) {
        LocalDate dealing =
            DealingDateRule.dealingDateFor(valueDate, morningOf(valueDate), lag, calendar);
        assertThat(calendar.isBusinessDate(dealing))
            .withFailMessage(
                "valueDate=%s lag=%d produced non-business date %s", valueDate, lag, dealing)
            .isTrue();
      }
    }
  }
}
