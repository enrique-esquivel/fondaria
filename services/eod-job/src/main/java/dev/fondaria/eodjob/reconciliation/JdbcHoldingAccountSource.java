package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link HoldingAccountSource} over {@code workflow.allotment}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <h2>Why a row-wise keyset predicate rather than {@code OFFSET}</h2>
 *
 * <p>{@code (a, b, c) > (:a, :b, :c)} is a single row comparison, and PostgreSQL serves it from the
 * leading columns of a multicolumn index — {@code allotment_by_holding}, added by {@code V25} for
 * exactly this scan. Each page is therefore a bounded index walk that starts where the last one
 * stopped. {@code OFFSET} would re-read and re-aggregate everything before the page on every page,
 * which is quadratic in the number of participants and so is wrong at precisely the scale a close
 * runs at.
 *
 * <p>{@code DISTINCT} rather than {@code GROUP BY}: a participant appears once per (batch, source)
 * they were ever allotted under, and the tie-out wants each holding once. The units on those rows
 * are deliberately not read — this query supplies <b>keys, not quantities</b>. Summing {@code
 * workflow.allotment.units_scaled} would be a fourth derivation of units, and one that stops being
 * true the moment Phase 22 redeems anything.
 *
 * <p><b>Vesting is invisible here, and correctly so.</b> A {@code VESTING} transfer moves units
 * between a participant's unvested and vested accounts and writes no allotment row, so it changes
 * neither the candidate set nor the sum over the pair of accounts each key derives.
 *
 * <h2>{@code COLLATE "C"} on the participant reference (added by Task 7.4)</h2>
 *
 * <p>Any total order pages correctly, so the tie-out never cared which one. The dense snapshot
 * does: it writes its rows in exactly this order and hashes the result, and "a re-run produces an
 * identical hash" then rests on the order being a property of the <i>data</i>. Under the database's
 * default collation it is a property of the installed locale — a glibc or ICU upgrade reorders
 * references differing by case or punctuation ({@code EMP.001} against {@code EMP-001}), every past
 * date stops reproducing, and the close halts on something that reads exactly like corruption.
 * {@code "C"} is byte order, which no upgrade changes.
 *
 * <p>The predicate and the {@code ORDER BY} must carry the collation together, and so must {@code
 * JdbcCostBasisSource}'s range over the same three columns — a page whose boundaries are ordered
 * one way and whose lots are selected another silently drops a participant's cost basis.
 */
@Repository
public class JdbcHoldingAccountSource implements HoldingAccountSource {

  // The collation is applied in the SELECT list, not in the ORDER BY: under SELECT DISTINCT,
  // PostgreSQL requires every ORDER BY expression to appear in the select list, and
  // `participant_internal_ref COLLATE "C"` is a different expression from the bare column. Naming
  // the collated column here makes ORDER BY refer to the output column, which already carries it.
  private static final String FIRST_PAGE =
      """
      SELECT DISTINCT organization_id,
             participant_internal_ref COLLATE "C" AS participant_internal_ref,
             fund_id
        FROM workflow.allotment
       ORDER BY organization_id, participant_internal_ref, fund_id
       LIMIT :limit
      """;

  private static final String NEXT_PAGE =
      """
      SELECT DISTINCT organization_id,
             participant_internal_ref COLLATE "C" AS participant_internal_ref,
             fund_id
        FROM workflow.allotment
       WHERE (organization_id, participant_internal_ref COLLATE "C", fund_id)
             > (:afterOrganizationId, :afterParticipantRef COLLATE "C", :afterFundId)
       ORDER BY organization_id, participant_internal_ref, fund_id
       LIMIT :limit
      """;

  private final JdbcClient jdbc;

  public JdbcHoldingAccountSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<HoldingKey> nextPage(Optional<HoldingKey> after, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return after
        .map(
            cursor ->
                jdbc.sql(NEXT_PAGE)
                    .param("afterOrganizationId", cursor.organizationId().value())
                    .param("afterParticipantRef", cursor.participantInternalRef())
                    .param("afterFundId", cursor.fundId().value())
                    .param("limit", limit)
                    .query(JdbcHoldingAccountSource::mapKey)
                    .list())
        .orElseGet(
            () ->
                jdbc.sql(FIRST_PAGE)
                    .param("limit", limit)
                    .query(JdbcHoldingAccountSource::mapKey)
                    .list());
  }

  private static HoldingKey mapKey(ResultSet rs, int rowNum) throws SQLException {
    return new HoldingKey(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        new FundId(rs.getObject("fund_id", UUID.class)));
  }
}
