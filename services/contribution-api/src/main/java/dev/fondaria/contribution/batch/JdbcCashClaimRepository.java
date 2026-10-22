package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CashClaimRepository} over {@code workflow.contribution_batch_cash_claim} and the unclaimed
 * side of {@code workflow.cash_receipt}.
 *
 * <p><b>No query in this class selects an amount.</b> Not {@code amount_minor_units}, not a {@code
 * SUM}, not a {@code MAX}. It answers only "which receipts exist and which are spoken for"; how
 * much each one was is read from the ledger by {@link ReconciliationGate}. The absence is the
 * design, not an oversight — see ADR-0008 invariant 1.
 */
@Repository
public class JdbcCashClaimRepository implements CashClaimRepository {

  private final JdbcClient jdbc;

  public JdbcCashClaimRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<UnclaimedReceipt> unclaimed(OrganizationId organizationId) {
    return jdbc.sql(
            """
            SELECT r.transfer_id, r.value_date, r.updated_at
              FROM workflow.cash_receipt r
             WHERE r.organization_id = :organizationId
               AND r.status = 'POSTED'
               AND NOT EXISTS (
                   SELECT 1
                     FROM workflow.contribution_batch_cash_claim c
                    WHERE c.organization_id = r.organization_id
                      AND c.transfer_id = r.transfer_id)
             ORDER BY r.value_date, r.updated_at
            """)
        .param("organizationId", organizationId.value())
        .query(JdbcCashClaimRepository::mapUnclaimed)
        .list();
  }

  /**
   * One transaction, so the claim is all or nothing. No {@code ON CONFLICT} clause anywhere: a
   * conflict here means another batch has taken one of these receipts since they were listed, and
   * that must fail loudly and roll the whole claim back. Swallowing it would leave a batch marked
   * funded by money it does not have.
   */
  @Override
  @Transactional
  public void claim(
      OrganizationId organizationId, UUID batchId, List<TransferId> transferIds, Instant now) {
    if (transferIds.isEmpty()) {
      throw new IllegalArgumentException("A batch claims at least one receipt or none at all");
    }
    for (TransferId transferId : transferIds) {
      jdbc.sql(
              """
              INSERT INTO workflow.contribution_batch_cash_claim (
                  organization_id, transfer_id, batch_id, claimed_at)
              VALUES (:organizationId, :transferId, :batchId, :claimedAt)
              """)
          .param("organizationId", organizationId.value())
          .param("transferId", TransferIdDeriver.toUuid(transferId))
          .param("batchId", batchId)
          .param("claimedAt", Timestamp.from(now))
          .update();
    }
  }

  @Override
  public List<TransferId> claimedBy(UUID batchId) {
    return jdbc.sql(
            """
            SELECT transfer_id
              FROM workflow.contribution_batch_cash_claim
             WHERE batch_id = :batchId
             ORDER BY claimed_at
            """)
        .param("batchId", batchId)
        .query(
            (ResultSet rs, int rowNum) ->
                TransferIdDeriver.toTransferId(rs.getObject("transfer_id", UUID.class)))
        .list();
  }

  private static UnclaimedReceipt mapUnclaimed(ResultSet rs, int rowNum) throws SQLException {
    return new UnclaimedReceipt(
        TransferIdDeriver.toTransferId(rs.getObject("transfer_id", UUID.class)),
        rs.getObject("value_date", LocalDate.class),
        rs.getTimestamp("updated_at").toInstant());
  }
}
