package dev.fondaria.rules;

import dev.fondaria.common.BusinessCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Which valuation date a staged contribution batch deals at — the NAV strike whose price its
 * participants will be allotted units at (phase-21-allotment.md, Task 21.1).
 *
 * <p>Static and pure, like {@link MatchingRatioRule}. <b>Nothing here reads the system clock</b>,
 * and nothing here may: {@link LocalDate#now()} and {@link Instant#now()} would make a batch's
 * dealing date depend on when somebody happened to ask, so the same batch would answer differently
 * to a status query and to the allotment run that prices it. Both inputs are supplied — the value
 * date the employer declared, and the instant the batch became dealable — which is also what lets a
 * simulated clock drive the demo without this rule knowing one exists.
 *
 * <h2>The rule</h2>
 *
 * <pre>
 *   target   = valueDate rolled forward to a business date, then advanced
 *              dealingLagBusinessDays business days
 *   earliest = calendar.businessDateOf(readyAt)     // cut-off lives here
 *   dealing  = max(target, earliest)
 * </pre>
 *
 * <p><b>Why the maximum, and not simply the target.</b> A batch that misses cut-off deals at the
 * next valuation date — it is never dropped and never back-dated. Back-dating is the one outcome
 * that is actually wrong rather than merely late: it would allot units at a price struck before the
 * money was known to be there, handing the participant a stale price chosen with today's
 * information. Taking the later of the two makes cut-off a floor on the answer and leaves the
 * plan's lag in charge whenever the batch is early, which is the ordinary case.
 *
 * <p>Cut-off itself is entirely {@link BusinessCalendar}'s: a timestamp at or after the configured
 * cut-off time belongs to the next business date, and the result is rolled past weekends and
 * holidays either way. This class adds no second notion of "late".
 */
public final class DealingDateRule {

  /**
   * The largest lag a plan may declare, mirrored by {@code reference.plan_version}'s {@code
   * plan_version_dealing_lag_in_range} check. Advancing a lag is a loop over the calendar, so an
   * unbounded value would turn reading a batch's status into a spin; a lag beyond a month is a
   * defect rather than a business arrangement.
   */
  public static final int MAXIMUM_DEALING_LAG_BUSINESS_DAYS = 30;

  private DealingDateRule() {}

  /**
   * The valuation date {@code valueDate} deals at.
   *
   * @param valueDate the batch's declared value date. Rolled forward when it falls on a weekend or
   *     a market holiday — a batch dated on a day the market was shut deals at the next valuation
   *     date rather than being refused.
   * @param readyAt the instant the batch became dealable (its {@code staged_at}). Cut-off is
   *     evaluated against this and nothing else.
   * @param dealingLagBusinessDays business days between the value date and the date it deals at.
   *     Zero is same-day dealing.
   * @return a date that is always a business date, and never earlier than {@code readyAt}'s own
   *     business date
   */
  public static LocalDate dealingDateFor(
      LocalDate valueDate, Instant readyAt, int dealingLagBusinessDays, BusinessCalendar calendar) {
    Objects.requireNonNull(valueDate, "valueDate must not be null");
    Objects.requireNonNull(readyAt, "readyAt must not be null");
    Objects.requireNonNull(calendar, "calendar must not be null");
    if (dealingLagBusinessDays < 0) {
      throw new IllegalArgumentException("dealingLagBusinessDays must not be negative");
    }
    if (dealingLagBusinessDays > MAXIMUM_DEALING_LAG_BUSINESS_DAYS) {
      throw new IllegalArgumentException(
          "dealingLagBusinessDays must not exceed " + MAXIMUM_DEALING_LAG_BUSINESS_DAYS);
    }

    LocalDate target = firstBusinessDateOnOrAfter(valueDate, calendar);
    for (int advanced = 0; advanced < dealingLagBusinessDays; advanced++) {
      target = calendar.nextBusinessDate(target);
    }

    LocalDate earliest = calendar.businessDateOf(readyAt);
    return target.isAfter(earliest) ? target : earliest;
  }

  /**
   * {@code date} itself when it is a business date, otherwise the next one.
   *
   * <p>{@link BusinessCalendar#nextBusinessDate} is strictly-after by contract, so calling it
   * unconditionally would push every ordinary Tuesday batch to Wednesday — the lag would be one day
   * larger than the plan says. The guard is the difference between "roll a holiday forward" and
   * "add a day", and only the first is wanted here.
   */
  private static LocalDate firstBusinessDateOnOrAfter(LocalDate date, BusinessCalendar calendar) {
    return calendar.isBusinessDate(date) ? date : calendar.nextBusinessDate(date);
  }
}
