package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CustodianHoldingSource} over {@code workflow.allotment}, using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>Unpaged, unlike {@code JdbcHoldingAccountSource}: that scan is platform-wide and must be
 * keyset-paged at scale (Task 7.2); this one is bounded by a single organization's participants in
 * a single fund, which {@code allotment_by_holding} (V25) already serves as a direct index range
 * scan.
 */
@Repository
public class JdbcCustodianHoldingSource implements CustodianHoldingSource {

  private final JdbcClient jdbc;

  public JdbcCustodianHoldingSource(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<HoldingKey> holdingsOf(OrganizationId organizationId, FundId fundId) {
    return jdbc.sql(
            """
            SELECT DISTINCT organization_id, participant_internal_ref, fund_id
              FROM workflow.allotment
             WHERE organization_id = :organizationId
               AND fund_id = :fundId
            """)
        .param("organizationId", organizationId.value())
        .param("fundId", fundId.value())
        .query(JdbcCustodianHoldingSource::mapKey)
        .list();
  }

  private static HoldingKey mapKey(ResultSet rs, int rowNum) throws SQLException {
    return new HoldingKey(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        new FundId(rs.getObject("fund_id", UUID.class)));
  }
}
