package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.FundId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AcceptedValuationRepository} over {@code workflow.valuation}, using {@code JdbcClient}
 * (AGENTS.md §5 — no ORM).
 *
 * <p><b>No {@code UPDATE} and no {@code DELETE} appear in this class, and none may be added.</b> A
 * live valuation is never overwritten: an identical redelivery is filtered out before it reaches
 * here, and a differing one is refused as {@code VALUATION_ALREADY_LIVE} so it goes through Task
 * 20.5's correction path. The unique constraint on {@code (fund_id, valuation_date)} is what makes
 * that structural rather than merely intended.
 *
 * <p><b>Nothing here sums a reported value.</b> A reported balance is one provider's statement
 * about one fund on one date; adding two of them together answers no question anybody asked.
 */
@Repository
public class JdbcAcceptedValuationRepository implements AcceptedValuationRepository {

  private static final String SELECT_VALUATION =
      """
      SELECT valuation_id, batch_id, fund_id, valuation_date,
             reported_value_minor_units, provider_reference, received_at
        FROM workflow.valuation
      """;

  private final JdbcClient jdbc;

  public JdbcAcceptedValuationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AcceptedValuation> find(FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(
            SELECT_VALUATION + " WHERE fund_id = :fundId AND valuation_date = :valuationDate")
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .query(JdbcAcceptedValuationRepository::mapValuation)
        .optional();
  }

  /**
   * One transaction for the whole file: a valuation file is all-or-nothing ({@code
   * valuation-file-v1.md} §4), so a crash midway must leave no fund priced on a delivery the batch
   * never accepted.
   *
   * <p>No {@code ON CONFLICT} clause, deliberately. By the time this runs the validator has already
   * established that every row is either new or an identical no-op, and the no-ops are not in this
   * collection. A conflict here therefore means two batches raced for the same {@code (fund,
   * date)}, and the right outcome is that one of them fails and is retried — not that it silently
   * does nothing and reports success.
   */
  @Override
  @Transactional
  public int insertAll(Collection<AcceptedValuation> valuations) {
    int inserted = 0;
    for (AcceptedValuation valuation : valuations) {
      inserted +=
          jdbc.sql(
                  """
                  INSERT INTO workflow.valuation (
                      valuation_id, batch_id, fund_id, valuation_date,
                      reported_value_minor_units, provider_reference, received_at)
                  VALUES (:valuationId, :batchId, :fundId, :valuationDate,
                          :reportedValueMinorUnits, :providerReference, :receivedAt)
                  """)
              .param("valuationId", valuation.valuationId())
              .param("batchId", valuation.batchId())
              .param("fundId", valuation.fundId().value())
              .param("valuationDate", valuation.valuationDate())
              .param("reportedValueMinorUnits", valuation.reportedValueMinorUnits())
              .param("providerReference", valuation.providerReference())
              .param("receivedAt", Timestamp.from(valuation.receivedAt()))
              .update();
    }
    return inserted;
  }

  @Override
  public List<LocalDate> valuationDatesBetween(
      FundId fundId, LocalDate fromInclusive, LocalDate toInclusive) {
    return jdbc.sql(
            """
            SELECT valuation_date
              FROM workflow.valuation
             WHERE fund_id = :fundId
               AND valuation_date BETWEEN :fromInclusive AND :toInclusive
             ORDER BY valuation_date
            """)
        .param("fundId", fundId.value())
        .param("fromInclusive", fromInclusive)
        .param("toInclusive", toInclusive)
        .query((rs, rowNum) -> rs.getObject("valuation_date", LocalDate.class))
        .list();
  }

  private static AcceptedValuation mapValuation(ResultSet rs, int rowNum) throws SQLException {
    return new AcceptedValuation(
        rs.getObject("valuation_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getObject("valuation_date", LocalDate.class),
        rs.getLong("reported_value_minor_units"),
        rs.getString("provider_reference"),
        rs.getTimestamp("received_at").toInstant());
  }
}
