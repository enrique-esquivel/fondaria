package dev.fondaria.eodjob.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CloseWindowTest {

  private static final ZoneId ZONE = ZoneId.of("America/Mexico_City");
  private static final BusinessCalendar CALENDAR =
      new BusinessCalendar(ZONE, LocalTime.of(18, 0), Set.of());
  private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
  private static final LocalTime START = LocalTime.of(17, 0);

  private final Set<LocalDate> signedOff = new HashSet<>();

  private static Clock at(LocalDate date, LocalTime time) {
    return Clock.fixed(date.atTime(time).atZone(ZONE).toInstant(), ZONE);
  }

  private CloseWindow window(Clock clock) {
    return CloseWindow.openingAt(START, ZONE, CALENDAR, clock, signedOff::contains);
  }

  @Test
  @DisplayName("with no start configured, every sweep is admitted at any hour")
  void noStartAdmitsEverything() {
    CloseWindow always = CloseWindow.always(ZONE, CALENDAR, at(MONDAY, LocalTime.of(0, 30)));

    assertThat(always.admitsControls()).isTrue();
    assertThat(always.admitsSignoff()).isTrue();
    assertThat(always.start()).isEmpty();
  }

  @Test
  @DisplayName("before the window opens, the sign-off table is not read at all")
  void shutWindowNeverReadsSignoffs() {
    AtomicInteger reads = new AtomicInteger();
    CloseWindow window =
        CloseWindow.openingAt(
            START,
            ZONE,
            CALENDAR,
            at(MONDAY, LocalTime.of(9, 0)),
            date -> reads.incrementAndGet() > 0);

    assertThat(window.admitsControls()).isFalse();
    assertThat(reads).hasValue(0);
  }

  @Test
  @DisplayName("at 09:00, where `demo-tools advance` lands, the window is shut")
  void shutBeforeTheStart() {
    CloseWindow window = window(at(MONDAY, LocalTime.of(9, 0)));

    assertThat(window.admitsControls()).isFalse();
    assertThat(window.admitsSignoff()).isFalse();
  }

  @Test
  @DisplayName("from the start until cut-off, the window is open")
  void openFromTheStartUntilCutoff() {
    assertThat(window(at(MONDAY, START)).admitsControls()).isTrue();
    assertThat(window(at(MONDAY, LocalTime.of(17, 59, 59))).admitsSignoff()).isTrue();
  }

  @Test
  @DisplayName("at cut-off the instant belongs to the next date, whose day has not begun")
  void shutAtCutoff() {
    CloseWindow window = window(at(MONDAY, LocalTime.of(18, 0)));

    assertThat(window.admitsControls()).isFalse();
    assertThat(window.admitsSignoff()).isFalse();
  }

  @Test
  @DisplayName("a weekend never opens a window: Saturday belongs to Monday's business date")
  void weekendIsShut() {
    LocalDate saturday = LocalDate.of(2026, 10, 31);

    assertThat(window(at(saturday, LocalTime.of(17, 30))).admitsSignoff()).isFalse();
  }

  @Test
  @DisplayName("once the date is signed off the controls stop, but sign-off keeps running")
  void controlsStopOnceSignedOff() {
    signedOff.add(MONDAY);
    CloseWindow window = window(at(MONDAY, LocalTime.of(17, 30)));

    assertThat(window.admitsControls()).isFalse();
    assertThat(window.admitsSignoff()).isTrue();
  }

  @Test
  @DisplayName("a sign-off of another date does not stop today's controls")
  void anotherDatesSignoffDoesNotCount() {
    signedOff.add(MONDAY.minusDays(3));

    assertThat(window(at(MONDAY, LocalTime.of(17, 30))).admitsControls()).isTrue();
  }
}
