package dev.fondaria.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class DemoCalendarTest {

  private static final BusinessCalendar CALENDAR = DemoCalendar.create();

  @Test
  void lastBusinessDayRollsBackOverAWeekend() {
    assertThat(DemoCalendar.lastBusinessDayOf(CALENDAR, YearMonth.of(2026, 10)))
        .isEqualTo("2026-10-30");
    assertThat(DemoCalendar.lastBusinessDayOf(CALENDAR, YearMonth.of(2026, 11)))
        .isEqualTo("2026-11-30");
  }

  @Test
  void onlyTheLastBusinessDayIsAMonthEnd() {
    assertThat(DemoCalendar.isLastBusinessDayOfMonth(CALENDAR, LocalDate.of(2026, 10, 30)))
        .isTrue();
    assertThat(DemoCalendar.isLastBusinessDayOfMonth(CALENDAR, LocalDate.of(2026, 10, 29)))
        .isFalse();
  }
}
