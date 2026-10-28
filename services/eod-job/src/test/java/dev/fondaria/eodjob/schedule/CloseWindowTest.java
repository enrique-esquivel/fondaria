package dev.fondaria.eodjob.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CloseWindowTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(ZONE, LocalTime.of(18, 0), Set.of());
  private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);
  private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
  private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 27);
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 28);
  private static final LocalTime START = LocalTime.of(17, 0);

  private final TreeSet<LocalDate> signedOff = new TreeSet<>();

  private static Clock at(LocalDate date, LocalTime time) {
    return Clock.fixed(date.atTime(time).atZone(ZONE).toInstant(), ZONE);
  }

  private Optional<LocalDate> latest() {
    return signedOff.isEmpty() ? Optional.empty() : Optional.of(signedOff.last());
  }

  private CloseWindow window(Clock clock) {
    return CloseWindow.openingAt(START, ZONE, CALENDAR, clock, this::latest);
  }

  private CloseWindow always(Clock clock) {
    return CloseWindow.always(ZONE, CALENDAR, clock, this::latest);
  }

  @Nested
  @DisplayName("today's close")
  class Today {

    @Test
    @DisplayName("with no start configured, every sweep is admitted at any hour")
    void noStartAdmitsEverything() {
      CloseWindow always = always(at(MONDAY, LocalTime.of(0, 30)));

      assertThat(always.dateForControls()).contains(MONDAY);
      assertThat(always.dateForSignoff()).contains(MONDAY);
      assertThat(always.start()).isEmpty();
    }

    @Test
    @DisplayName("at 09:00, where `demo-tools advance` lands, the window is shut")
    void shutBeforeTheStart() {
      CloseWindow window = window(at(MONDAY, LocalTime.of(9, 0)));

      assertThat(window.dateForControls()).isEmpty();
      assertThat(window.dateForSignoff()).isEmpty();
    }

    @Test
    @DisplayName("from the start until cut-off, the window is open")
    void openFromTheStartUntilCutoff() {
      assertThat(window(at(MONDAY, START)).dateForControls()).contains(MONDAY);
      assertThat(window(at(MONDAY, LocalTime.of(17, 59, 59))).dateForSignoff()).contains(MONDAY);
    }

    @Test
    @DisplayName("at cut-off with today signed off, the next date's day has not begun")
    void shutAtCutoffOnceSignedOff() {
      signedOff.add(MONDAY);
      CloseWindow window = window(at(MONDAY, LocalTime.of(18, 0)));

      assertThat(window.closingDate()).isEqualTo(TUESDAY);
      assertThat(window.dateForControls()).isEmpty();
      assertThat(window.dateForSignoff()).isEmpty();
    }

    @Test
    @DisplayName("a weekend never opens a window: Saturday belongs to Monday's business date")
    void weekendIsShut() {
      signedOff.add(LocalDate.of(2026, 10, 30));
      LocalDate saturday = LocalDate.of(2026, 10, 31);

      assertThat(window(at(saturday, LocalTime.of(17, 30))).dateForSignoff()).isEmpty();
    }

    @Test
    @DisplayName("once the date is signed off the controls stop, but sign-off keeps running")
    void controlsStopOnceSignedOff() {
      signedOff.add(MONDAY);
      CloseWindow window = window(at(MONDAY, LocalTime.of(17, 30)));

      assertThat(window.dateForControls()).isEmpty();
      assertThat(window.dateForSignoff()).contains(MONDAY);
    }

    @Test
    @DisplayName("the previous business date's sign-off does not stop today's controls")
    void anotherDatesSignoffDoesNotCount() {
      signedOff.add(FRIDAY);

      assertThat(window(at(MONDAY, LocalTime.of(17, 30))).dateForControls()).contains(MONDAY);
    }

    @Test
    @DisplayName("with nothing ever signed off, the close works on today")
    void genesisIsToday() {
      CloseWindow window = window(at(MONDAY, LocalTime.of(18, 3)));

      assertThat(window.closingDate()).isEqualTo(TUESDAY);
      assertThat(window.businessDatesBehind()).isZero();
    }
  }

  @Nested
  @DisplayName("a date still unsigned after its cut-off")
  class PastDate {

    @Test
    @DisplayName("at 18:03 Monday, Monday is still the closing date and the window admits it")
    void tiesJustAfterCutoff() {
      // 2026-10-27's load run: the date's breaks at 17:58 were the projection still draining.
      signedOff.add(FRIDAY);
      CloseWindow window = window(at(MONDAY, LocalTime.of(18, 3)));

      assertThat(window.closingDate()).isEqualTo(MONDAY);
      assertThat(window.dateForControls()).contains(MONDAY);
      assertThat(window.dateForSignoff()).contains(MONDAY);
      assertThat(window.businessDatesBehind()).isEqualTo(1L);
    }

    @Test
    @DisplayName("next morning, before the window opens, Monday is still worked on — not Tuesday")
    void beforeTheNextWindowOpens() {
      signedOff.add(FRIDAY);
      CloseWindow window = window(at(TUESDAY, LocalTime.of(9, 0)));

      assertThat(window.dateForControls()).contains(MONDAY);
      assertThat(window.dateForSignoff()).contains(MONDAY);
    }

    @Test
    @DisplayName("inside the next date's window, the close still does not move past Monday")
    void neverSkipsAhead() {
      signedOff.add(FRIDAY);
      CloseWindow window = window(at(TUESDAY, LocalTime.of(17, 30)));

      assertThat(window.dateForControls()).contains(MONDAY);
      assertThat(window.dateForSignoff()).contains(MONDAY);
    }

    @Test
    @DisplayName("with no window configured, the earliest unsigned date is closed first too")
    void noWindowIsInOrderToo() {
      signedOff.add(FRIDAY);
      CloseWindow always = always(at(WEDNESDAY, LocalTime.of(10, 0)));

      assertThat(always.dateForControls()).contains(MONDAY);
      assertThat(always.dateForSignoff()).contains(MONDAY);
      assertThat(always.businessDatesBehind()).isEqualTo(2L);
    }

    @Test
    @DisplayName("once Monday signs off, the close moves to Tuesday — in Tuesday's window only")
    void movesOnOnceSignedOff() {
      signedOff.add(FRIDAY);
      signedOff.add(MONDAY);

      assertThat(window(at(TUESDAY, LocalTime.of(9, 0))).dateForControls()).isEmpty();
      assertThat(window(at(TUESDAY, LocalTime.of(17, 30))).dateForControls()).contains(TUESDAY);
      assertThat(window(at(TUESDAY, LocalTime.of(9, 0))).businessDatesBehind()).isZero();
    }

    @Test
    @DisplayName("a weekend between is not a date behind: Friday unsigned on Monday is one date")
    void weekendsAreNotCounted() {
      signedOff.add(LocalDate.of(2026, 10, 22));
      CloseWindow window = window(at(MONDAY, LocalTime.of(10, 0)));

      assertThat(window.closingDate()).isEqualTo(FRIDAY);
      assertThat(window.businessDatesBehind()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a hole behind the latest sign-off is never revisited")
    void holesBehindTheLatestAreNotRevisited() {
      // Re-opening a past sign-off is a deliberate DELETE (RUNBOOKS "missed sign-off"); the close
      // must not silently sign that date off again.
      signedOff.add(FRIDAY);
      signedOff.add(TUESDAY);
      CloseWindow window = window(at(WEDNESDAY, LocalTime.of(17, 30)));

      assertThat(window.closingDate()).isEqualTo(WEDNESDAY);
      assertThat(window.dateForControls()).contains(WEDNESDAY);
    }
  }
}
