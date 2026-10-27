package dev.fondaria.demo.advance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.demo.DemoCalendar;
import dev.fondaria.demo.DemoToolsException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdvanceCommandTest {

  private static final BusinessCalendar CALENDAR = DemoCalendar.create();
  private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 27);

  @Test
  @DisplayName("--business-days N moves N business days, skipping the weekend")
  void businessDays() {
    assertThat(AdvanceCommand.targetDate(CALENDAR, TUESDAY, 1, "")).isEqualTo("2026-10-28");
    assertThat(AdvanceCommand.targetDate(CALENDAR, TUESDAY, 4, "")).isEqualTo("2026-11-02");
  }

  @Test
  @DisplayName(
      "--to month-end lands on the month's last business day, a Friday when the 31st is not")
  void monthEndFromMidMonth() {
    // 2026-10-31 is a Saturday: October's statements are fanned out by Friday the 30th's close.
    assertThat(AdvanceCommand.targetDate(CALENDAR, TUESDAY, 1, "month-end"))
        .isEqualTo("2026-10-30");
  }

  @Test
  @DisplayName("--to month-end from a month-end moves to the next month's, never re-running a day")
  void monthEndFromMonthEnd() {
    assertThat(AdvanceCommand.targetDate(CALENDAR, LocalDate.of(2026, 10, 30), 1, "month-end"))
        .isEqualTo("2026-11-30");
  }

  @Test
  @DisplayName("an unknown --to, or fewer than one business day, is refused")
  void refusesNonsense() {
    assertThatThrownBy(() -> AdvanceCommand.targetDate(CALENDAR, TUESDAY, 1, "year-end"))
        .isInstanceOf(DemoToolsException.class);
    assertThatThrownBy(() -> AdvanceCommand.targetDate(CALENDAR, TUESDAY, 0, ""))
        .isInstanceOf(DemoToolsException.class);
  }

  @Test
  @DisplayName("the offset is the target minus real now, in seconds")
  void offsetFor() {
    ZonedDateTime target =
        ZonedDateTime.of(TUESDAY, AdvanceCommand.START_OF_DAY, DemoCalendar.ZONE);
    Instant now = target.toInstant().minusSeconds(3_600);

    assertThat(DemoClock.offsetFor(target, now)).isEqualTo(3_600L);
  }
}
