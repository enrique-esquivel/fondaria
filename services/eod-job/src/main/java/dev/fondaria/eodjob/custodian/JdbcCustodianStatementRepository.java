package dev.fondaria.eodjob.custodian;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CustodianStatementRepository} over {@code workflow.custodian_statement} ({@code V27}),
 * using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
public class JdbcCustodianStatementRepository implements CustodianStatementRepository {

  private final JdbcClient jdbc;

  public JdbcCustodianStatementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CustodianStatement> findFor(UUID providerAccountId, LocalDate businessDate) {
    return jdbc.sql(
            """
            SELECT statement_id, provider_account_id, business_date, reported_units_scaled,
                   reported_cash_cents, pending_trade_units_scaled, pending_trade_cash_cents,
                   source, checksum_sha256, received_at
              FROM workflow.custodian_statement
             WHERE provider_account_id = :providerAccountId
               AND business_date = :businessDate
            """)
        .param("providerAccountId", providerAccountId)
        .param("businessDate", businessDate)
        .query(JdbcCustodianStatementRepository::mapStatement)
        .optional();
  }

  @Override
  public CustodianStatement insert(
      UUID statementId, CustodianStatementSubmission submission, Instant receivedAt) {
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_statement (
                statement_id, provider_account_id, business_date, reported_units_scaled,
                reported_cash_cents, pending_trade_units_scaled, pending_trade_cash_cents,
                source, checksum_sha256, received_at)
            VALUES (
                :statementId, :providerAccountId, :businessDate, :reportedUnits,
                :reportedCash, :pendingTradeUnits, :pendingTradeCash,
                :source, :checksum, :receivedAt)
            """)
        .param("statementId", statementId)
        .param("providerAccountId", submission.providerAccountId())
        .param("businessDate", submission.businessDate())
        .param("reportedUnits", submission.reportedUnitsScaled())
        .param("reportedCash", submission.reportedCashCents())
        .param("pendingTradeUnits", submission.pendingTradeUnitsScaled())
        .param("pendingTradeCash", submission.pendingTradeCashCents())
        .param("source", submission.source())
        .param("checksum", submission.checksumSha256())
        .param("receivedAt", Timestamp.from(receivedAt))
        .update();
    return new CustodianStatement(
        statementId,
        submission.providerAccountId(),
        submission.businessDate(),
        submission.reportedUnitsScaled(),
        submission.reportedCashCents(),
        submission.pendingTradeUnitsScaled(),
        submission.pendingTradeCashCents(),
        submission.source(),
        submission.checksumSha256(),
        receivedAt);
  }

  private static CustodianStatement mapStatement(ResultSet rs, int rowNum) throws SQLException {
    return new CustodianStatement(
        rs.getObject("statement_id", UUID.class),
        rs.getObject("provider_account_id", UUID.class),
        rs.getObject("business_date", LocalDate.class),
        rs.getLong("reported_units_scaled"),
        rs.getLong("reported_cash_cents"),
        rs.getLong("pending_trade_units_scaled"),
        rs.getLong("pending_trade_cash_cents"),
        rs.getString("source"),
        rs.getString("checksum_sha256"),
        rs.getTimestamp("received_at").toInstant());
  }
}
