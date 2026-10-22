package dev.fondaria.valuationapi.correction;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AffectedAllotmentRepository} over {@code workflow.allotment}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@code allotment_by_nav_version} (V19) is what keeps this a lookup rather than a scan — the
 * only reason this service reads {@code workflow.allotment} at all is to answer this one question.
 */
@Repository
public class JdbcAffectedAllotmentRepository implements AffectedAllotmentRepository {

  private static final String SELECT_BY_NAV_VERSION =
      """
      SELECT organization_id, batch_id, participant_internal_ref, source, units_scaled, vested,
             transfer_id, allotted_at
        FROM workflow.allotment
       WHERE nav_version_id = :navVersionId
      """;

  private final JdbcClient jdbc;

  public JdbcAffectedAllotmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AffectedAllotment> findByNavVersionId(long navVersionId) {
    return jdbc.sql(SELECT_BY_NAV_VERSION)
        .param("navVersionId", navVersionId)
        .query(JdbcAffectedAllotmentRepository::mapRow)
        .list();
  }

  private static AffectedAllotment mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AffectedAllotment(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getObject("batch_id", UUID.class),
        rs.getString("participant_internal_ref"),
        rs.getString("source"),
        Units.of(rs.getLong("units_scaled")),
        rs.getBoolean("vested"),
        TransferIdDeriver.toTransferId(rs.getObject("transfer_id", UUID.class)),
        rs.getTimestamp("allotted_at").toInstant());
  }
}
