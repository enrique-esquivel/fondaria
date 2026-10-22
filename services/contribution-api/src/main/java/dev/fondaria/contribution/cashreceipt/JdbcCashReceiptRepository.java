package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CashReceiptRepository} over {@code workflow.cash_receipt}, using {@code JdbcClient}
 * (AGENTS.md §5 — no ORM).
 *
 * <p><b>No amount is ever summed here.</b> Every query is by primary key. A balance comes from
 * {@code LedgerPort.lookupAccount} and from nowhere else, and a {@code SUM(amount_minor_units)}
 * added to this class later would be a second, wrong answer to a question the ledger already
 * answers correctly.
 *
 * <p><b>Money crosses this boundary as {@code long} cents.</b> {@code amount_minor_units} is {@code
 * bigint} and is read with {@code getLong}, so no {@code numeric}, {@code BigDecimal} or {@code
 * double} is involved at any point (AGENTS.md §4).
 */
@Repository
public class JdbcCashReceiptRepository implements CashReceiptRepository {

  private static final String SELECT_RECEIPT =
      """
      SELECT organization_id, idempotency_key, transfer_id, amount_minor_units, currency,
             value_date, bank_reference, operator_id, request_fingerprint, status,
             rejection_reason, created_at, updated_at
        FROM workflow.cash_receipt
      """;

  private final JdbcClient jdbc;

  public JdbcCashReceiptRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code REQUIRES_NEW}, deliberately. This row's whole purpose is to be durable before the ledger
   * is touched; joining a caller's transaction would leave it uncommitted for exactly the window it
   * exists to cover.
   *
   * <p><b>{@code ON CONFLICT DO NOTHING} carries no inference specification, and must not.</b>
   * Naming {@code (organization_id, idempotency_key)} suppresses conflicts on that index alone, and
   * PostgreSQL still raises on any other unique constraint — so two concurrent submissions of the
   * same receipt collided on {@code cash_receipt_transfer_id_unique} and one of them became a 500.
   * The bare form suppresses both, which is correct here rather than merely convenient: the
   * transfer id is derived from exactly those two columns, so a conflict on either constraint is
   * the same fact, "this receipt already exists".
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordUnconfirmed(CashReceipt receipt) {
    if (receipt.status() != CashReceiptStatus.UNCONFIRMED) {
      throw new IllegalArgumentException("A receipt is recorded as UNCONFIRMED and resolved later");
    }
    return jdbc.sql(
                """
            INSERT INTO workflow.cash_receipt (
                organization_id, idempotency_key, transfer_id, amount_minor_units, currency,
                value_date, bank_reference, operator_id, request_fingerprint, status,
                rejection_reason, created_at, updated_at)
            VALUES (:organizationId, :idempotencyKey, :transferId, :amountMinorUnits, :currency,
                    :valueDate, :bankReference, :operatorId, :requestFingerprint, :status,
                    NULL, :createdAt, :updatedAt)
            ON CONFLICT DO NOTHING
            """)
            .param("organizationId", receipt.organizationId().value())
            .param("idempotencyKey", receipt.idempotencyKey())
            .param("transferId", TransferIdDeriver.toUuid(receipt.transferId()))
            .param("amountMinorUnits", receipt.amount().cents())
            .param("currency", receipt.amount().currency().name())
            .param("valueDate", receipt.valueDate())
            .param("bankReference", receipt.bankReference())
            .param("operatorId", receipt.operatorId())
            .param("requestFingerprint", receipt.requestFingerprint())
            .param("status", receipt.status().name())
            .param("createdAt", Timestamp.from(receipt.createdAt()))
            .param("updatedAt", Timestamp.from(receipt.updatedAt()))
            .update()
        > 0;
  }

  /**
   * {@code FOR UPDATE NOWAIT}, not {@code FOR UPDATE}. Waiting would make a genuinely concurrent
   * duplicate queue behind the winner and then replay its result, which is a correct answer given
   * far too slowly — the second request would block for the whole of the first one's ledger call.
   * Failing immediately is what turns that case into the 409 this endpoint promises, and the lock
   * is released by the transaction ending, including when it ends because the process died.
   */
  @Override
  public Optional<CashReceipt> claim(OrganizationId organizationId, String idempotencyKey) {
    return jdbc.sql(
            SELECT_RECEIPT
                + """
                 WHERE organization_id = :organizationId
                   AND idempotency_key = :idempotencyKey
                   FOR UPDATE NOWAIT
                """)
        .param("organizationId", organizationId.value())
        .param("idempotencyKey", idempotencyKey)
        .query(JdbcCashReceiptRepository::mapReceipt)
        .optional();
  }

  /**
   * Guarded by {@code status = 'UNCONFIRMED'} rather than trusting the claim alone. Belt and
   * braces: a resolved receipt is final, and this makes a second resolution a no-op at the database
   * rather than a silent overwrite of an outcome that already reached a caller.
   */
  @Override
  public CashReceipt resolve(CashReceipt receipt) {
    if (receipt.status() == CashReceiptStatus.UNCONFIRMED) {
      throw new IllegalArgumentException("resolve moves a receipt out of UNCONFIRMED");
    }
    int updated =
        jdbc.sql(
                """
                UPDATE workflow.cash_receipt
                   SET status = :status,
                       rejection_reason = :rejectionReason,
                       updated_at = :updatedAt
                 WHERE organization_id = :organizationId
                   AND idempotency_key = :idempotencyKey
                   AND status = 'UNCONFIRMED'
                """)
            .param("organizationId", receipt.organizationId().value())
            .param("idempotencyKey", receipt.idempotencyKey())
            .param("status", receipt.status().name())
            .param("rejectionReason", receipt.rejectionReason().orElse(null), Types.VARCHAR)
            .param("updatedAt", Timestamp.from(receipt.updatedAt()))
            .update();
    if (updated == 0) {
      throw new IllegalStateException(
          "Cash receipt was no longer UNCONFIRMED when its outcome was recorded");
    }
    return receipt;
  }

  @Override
  public Optional<CashReceipt> find(OrganizationId organizationId, String idempotencyKey) {
    return jdbc.sql(
            SELECT_RECEIPT
                + " WHERE organization_id = :organizationId AND idempotency_key = :idempotencyKey")
        .param("organizationId", organizationId.value())
        .param("idempotencyKey", idempotencyKey)
        .query(JdbcCashReceiptRepository::mapReceipt)
        .optional();
  }

  private static CashReceipt mapReceipt(ResultSet rs, int rowNum) throws SQLException {
    TransferId transferId = TransferIdDeriver.toTransferId(rs.getObject("transfer_id", UUID.class));
    Money amount =
        Money.of(rs.getLong("amount_minor_units"), CurrencyCode.valueOf(rs.getString("currency")));
    return new CashReceipt(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("idempotency_key"),
        transferId,
        amount,
        rs.getObject("value_date", LocalDate.class),
        rs.getString("bank_reference"),
        rs.getString("operator_id"),
        rs.getString("request_fingerprint"),
        CashReceiptStatus.parse(rs.getString("status")),
        Optional.ofNullable(rs.getString("rejection_reason")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
