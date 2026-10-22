package dev.fondaria.statementworker.contribution;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.statementworker.ParticipantKey;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ContributionSource} over {@code workflow.allotment} joined to {@code reference.fund_nav}
 * (the strike price) and {@code reference.fund_identity} (the fund's currency, fixed at
 * registration and not carried on {@code fund_nav} itself), using {@code JdbcClient} (AGENTS.md §5
 * — no ORM).
 *
 * <h2>Filtered by the NAV's own valuation date for the month boundary, by {@code allotted_at} for
 * read consistency</h2>
 *
 * <p>The month boundary is {@code reference.fund_nav.valuation_date}, not {@code
 * workflow.allotment.allotted_at}: the business date the allotment's units were struck at,
 * effective-dated and stored, with no time zone to reason about — deriving a month boundary from an
 * {@code Instant} and a {@code BusinessCalendar} instead would be more machinery for the same
 * answer. But the range alone is not enough: an allotment can be written (and its NAV struck)
 * *after* the closing snapshot's pass already read {@code workflow.allotment}, with a {@code
 * valuation_date} that still falls inside this period. Its units are then absent from the closing
 * snapshot this class's caller reconciles against, so counting it here would make the
 * market-movement plug silently absorb a fabricated loss equal to the contribution. {@code
 * postedBefore} — the closing snapshot's own {@code started_at} — closes that gap the same way
 * {@code JdbcCostBasisSource} bounds the identical join by {@code allotted_at} for the identical
 * reason: a read must be consistent with the snapshot it is reconciled against, not just
 * date-ranged.
 *
 * <h2>No summation in SQL, deliberately</h2>
 *
 * <p>The same reasoning {@code JdbcCostBasisSource} gives: {@code Money.value} is exact {@code
 * BigInteger} arithmetic with half-up rounding on an explicit remainder, and reproducing that in a
 * {@code numeric} expression would be a second implementation of the platform's one money
 * multiplication. Summation ({@code Money::plus}) happens in Java, per row.
 */
@Repository
public class JdbcContributionSource implements ContributionSource {

  private static final String ALLOTMENTS_IN_RANGE =
      """
      SELECT a.organization_id, a.participant_internal_ref, a.source,
             a.units_scaled, n.nav_scaled, fi.base_currency AS currency
        FROM workflow.allotment a
        JOIN reference.fund_nav n ON n.nav_version_id = a.nav_version_id
        JOIN reference.fund_identity fi ON fi.fund_id = a.fund_id
       WHERE n.valuation_date > :openingBusinessDate
         AND n.valuation_date <= :closingBusinessDate
         AND a.allotted_at < :postedBefore
      """;

  private final JdbcClient jdbc;

  public JdbcContributionSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<ParticipantKey, ContributionTotals> totalsFor(
      LocalDate openingBusinessDate, LocalDate closingBusinessDate, Instant postedBefore) {
    Map<ParticipantKey, ContributionTotals> totals = new HashMap<>();
    jdbc.sql(ALLOTMENTS_IN_RANGE)
        .param("openingBusinessDate", openingBusinessDate)
        .param("closingBusinessDate", closingBusinessDate)
        .param("postedBefore", Timestamp.from(postedBefore))
        .query(
            (ResultSet rs, int rowNum) -> {
              ParticipantKey key =
                  new ParticipantKey(
                      new OrganizationId(rs.getObject("organization_id", UUID.class)),
                      rs.getString("participant_internal_ref"));
              CurrencyCode currency = CurrencyCode.valueOf(rs.getString("currency"));
              Money amount =
                  Money.value(
                      Units.of(rs.getLong("units_scaled")),
                      Nav.of(rs.getLong("nav_scaled"), currency));
              totals.merge(
                  key,
                  ContributionTotals.zero(currency).plus(rs.getString("source"), amount),
                  (existing, added) ->
                      new ContributionTotals(
                          existing.employee().plus(added.employee()),
                          existing.employer().plus(added.employer()),
                          existing.voluntary().plus(added.voluntary())));
              return null;
            })
        .list();
    return totals;
  }
}
