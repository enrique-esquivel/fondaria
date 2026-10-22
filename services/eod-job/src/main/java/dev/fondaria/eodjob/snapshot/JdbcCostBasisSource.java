package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CostBasisSource} over {@code workflow.allotment} joined to {@code reference.fund_nav},
 * using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <h2>The row-wise range is the page</h2>
 *
 * <p>{@code (a, b, c) BETWEEN (:from…) AND (:to…)} is written as two row comparisons because
 * PostgreSQL's {@code BETWEEN} does not accept row constructors. Both are served by {@code
 * allotment_by_holding} (V25), the index built for the close's keyset scan, so this reads exactly
 * the rows belonging to the page the scan just returned — inclusive at both ends, because the
 * page's first and last holdings are in the page.
 *
 * <p><b>{@code participant_internal_ref} is compared under {@code COLLATE "C"}, and must be.</b>
 * The page's boundaries come from {@code JdbcHoldingAccountSource}, which orders under the same
 * collation. Compare here under the database's default collation instead and the two disagree about
 * what "between the first and last holding of this page" means the moment a reference contains
 * punctuation or mixed case — silently dropping a participant's lots, and reporting their whole
 * position as unrealised gain.
 *
 * <h2>The join is to the NAV the allotment named</h2>
 *
 * <p>{@code nav_version_id}, not the fund's live price for that date. A corrected NAV supersedes
 * the row it replaces, but a lot was still issued at whatever price it was issued at — that is the
 * number {@code workflow.allotment} exists to preserve (docs/ENGINEERING-RULES.md §4: "every unit
 * allotment records which NAV version it used"). Valuing the lot at today's live price instead
 * would make cost basis move whenever the market did, and unrealised gain permanently zero.
 *
 * <p>No summation in SQL, deliberately. Cost basis is a sum of {@code Money.value} results, one per
 * lot, and {@code Money.value} is exact {@code BigInteger} arithmetic with half-up rounding on an
 * explicit remainder. Reproducing that rounding in a {@code numeric} expression would be a second
 * implementation of the platform's one money multiplication, and the two would drift by a cent on
 * the day someone changed one of them.
 */
@Repository
public class JdbcCostBasisSource implements CostBasisSource {

  private static final String LOTS_IN_RANGE =
      """
      SELECT a.organization_id, a.participant_internal_ref, a.fund_id,
             a.units_scaled, a.allotted_at, n.nav_scaled
        FROM workflow.allotment a
        JOIN reference.fund_nav n ON n.nav_version_id = a.nav_version_id
       WHERE (a.organization_id, a.participant_internal_ref COLLATE "C", a.fund_id)
             >= (:fromOrganizationId, :fromParticipantRef COLLATE "C", :fromFundId)
         AND (a.organization_id, a.participant_internal_ref COLLATE "C", a.fund_id)
             <= (:toOrganizationId, :toParticipantRef COLLATE "C", :toFundId)
         AND a.allotted_at < :postedBefore
       ORDER BY a.organization_id, a.participant_internal_ref COLLATE "C", a.fund_id
      """;

  private final JdbcClient jdbc;

  public JdbcCostBasisSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AllotmentLot> lotsFor(HoldingKey from, HoldingKey to, Instant postedBefore) {
    return jdbc.sql(LOTS_IN_RANGE)
        .param("fromOrganizationId", from.organizationId().value())
        .param("fromParticipantRef", from.participantInternalRef())
        .param("fromFundId", from.fundId().value())
        .param("toOrganizationId", to.organizationId().value())
        .param("toParticipantRef", to.participantInternalRef())
        .param("toFundId", to.fundId().value())
        .param("postedBefore", Timestamp.from(postedBefore))
        .query(JdbcCostBasisSource::mapLot)
        .list();
  }

  private static AllotmentLot mapLot(ResultSet rs, int rowNum) throws SQLException {
    return new AllotmentLot(
        new HoldingKey(
            new OrganizationId(rs.getObject("organization_id", UUID.class)),
            rs.getString("participant_internal_ref"),
            new FundId(rs.getObject("fund_id", UUID.class))),
        Units.of(rs.getLong("units_scaled")),
        rs.getLong("nav_scaled"),
        rs.getTimestamp("allotted_at").toInstant());
  }
}
