package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What one organization's people hold, and what its own money did this period (Task 6.5).
 *
 * <p><b>Two different kinds of number sit here, from two different authorities, and the difference
 * is deliberate.</b> {@code totalParticipants}, {@code totalValue} and {@code funds} are a position
 * — read from the ledger through {@code LedgerPort}, valued at the live NAV, never summed out of
 * PostgreSQL. {@code contributionsThisPeriod} is a flow — history, which only the CDC-fed
 * projection can answer, because the account that money passed through is back at zero.
 *
 * <p><b>Signed, like every value on this platform.</b> A fund whose NAV has fallen lowers the
 * organization's total exactly as it lowers each participant's, and nothing here clamps (AGENTS.md
 * §4).
 *
 * @param asOf when the read <b>began</b>. An instant, not a business date: both units and price are
 *     live. Not a snapshot instant — the roster is walked in slices, so a transfer committed
 *     mid-walk is counted for a late participant and missed for an early one. The tie to the
 *     participants' own balances is a quiescent-ledger property, which is what an employer
 *     aggregate needs: this book moves in batches at the strike, not continuously.
 * @param totalParticipants how many participants the totals were computed over — the count of the
 *     roster actually iterated, not a separate {@code COUNT(*)} that could disagree with it
 * @param period the days {@code contributionsThisPeriod} covers, reported so the figure is
 *     auditable rather than merely labelled
 * @param stale {@code true} when any fund's price is older than the configured age. Lifted to the
 *     top so a reader of the headline total alone cannot miss it; the per-fund flags say which.
 */
public record OrganizationSummary(
    OrganizationId organizationId,
    Instant asOf,
    int totalParticipants,
    Money totalValue,
    Money contributionsThisPeriod,
    StatementPeriod period,
    List<OrganizationFundTotal> funds,
    boolean stale) {

  public OrganizationSummary {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(asOf, "asOf must not be null");
    Objects.requireNonNull(totalValue, "totalValue must not be null");
    Objects.requireNonNull(contributionsThisPeriod, "contributionsThisPeriod must not be null");
    Objects.requireNonNull(period, "period must not be null");
    funds = List.copyOf(Objects.requireNonNull(funds, "funds must not be null"));
    if (totalParticipants < 0) {
      throw new IllegalArgumentException("totalParticipants must not be negative");
    }
  }

  /**
   * Sums the per-fund totals into the headline figure and lifts the staleness flag.
   *
   * <p>An organization whose participants hold nothing is {@code Money.zero(MXN)} and an empty fund
   * list — a real answer, distinct from the {@code 404} that says no such organization exists.
   */
  public static OrganizationSummary of(
      OrganizationId organizationId,
      Instant asOf,
      int totalParticipants,
      List<OrganizationFundTotal> funds,
      Money contributionsThisPeriod,
      StatementPeriod period) {
    Money total =
        funds.stream()
            .map(OrganizationFundTotal::totalValue)
            .reduce(Money.of(0, CurrencyCode.MXN), Money::plus);
    boolean stale = funds.stream().anyMatch(fund -> fund.nav().stale());
    return new OrganizationSummary(
        organizationId,
        asOf,
        totalParticipants,
        total,
        contributionsThisPeriod,
        period,
        funds,
        stale);
  }
}
