package dev.fondaria.demo;

import dev.fondaria.common.BusinessCalendar;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * The same cut-off every service in this platform uses (`phase-D-demo.md` § "Time, honestly":
 * "cut-off 18:00 America/Mexico_City"). No holiday calendar -- this module only needs weekday
 * rolling, and a demo run is short enough that a real holiday list would add configuration nobody
 * reads.
 */
public final class DemoCalendar {

  private DemoCalendar() {}

  public static BusinessCalendar create() {
    return new BusinessCalendar(ZoneId.of("America/Mexico_City"), LocalTime.of(18, 0), Set.of());
  }
}
