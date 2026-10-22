package dev.fondaria.demo.valuation;

import java.util.List;

/**
 * The scripted list of NAV moves `demo-day` cycles through (`phase-D-demo.md` Task D.2), fixed
 * rather than random so the demo is rehearsable: the same sequence every run, and it deliberately
 * includes falls -- `AGENTS.md` § Testing: "test suites must include falling and volatile NAV
 * series".
 *
 * <p>Each move is a signed integer scaled by {@link #MOVE_SCALE} (100,000) rather than a {@code
 * double} -- {@code AGENTS.md} §4 bans floating point for a "market-movement field" even here,
 * where the number only ever feeds a generated valuation file. {@code -1500} is -1.5%; {@code 300}
 * is +0.3%.
 */
public final class NavSeries {

  public static final long MOVE_SCALE = 100_000L;

  public static final List<Long> SCRIPTED_MOVES = List.of(300L, 100L, -800L, 200L, -1500L, 400L);

  private NavSeries() {}

  /**
   * The move at this series index, scaled by {@link #MOVE_SCALE}, cycling every {@code
   * SCRIPTED_MOVES.size()}.
   */
  public static long moveAt(int seriesIndex) {
    return SCRIPTED_MOVES.get(Math.floorMod(seriesIndex, SCRIPTED_MOVES.size()));
  }
}
