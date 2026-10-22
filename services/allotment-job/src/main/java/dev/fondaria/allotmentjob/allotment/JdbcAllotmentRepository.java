package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link AllotmentRepository} over {@code workflow.allotment} (V16), using {@code JdbcClient}
 * (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>{@code ON CONFLICT DO NOTHING} names the primary key explicitly, the same discipline {@code
 * JdbcContributionBatchRepository}'s javadoc records: an unqualified clause suppresses conflicts on
 * that index alone, and there is exactly one unique constraint this insert can ever hit (the
 * primary key), so naming it is what keeps a genuinely different conflict — one that should never
 * happen here — from being silently swallowed instead of surfaced.
 */
@Repository
public class JdbcAllotmentRepository implements AllotmentRepository {

  private final JdbcClient jdbc;

  public JdbcAllotmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Rows per {@code INSERT}. Ten bind parameters a row keeps a statement at 10,000 parameters, far
   * under the driver's bind limit (32,767 in older pgjdbc, 65,535 in PostgreSQL), while a full
   * allotment batch (~8,000 legs) still takes only a handful of round trips.
   */
  static final int ROWS_PER_STATEMENT = 1_000;

  /**
   * One multi-row {@code INSERT} per {@link #ROWS_PER_STATEMENT} rows, never one per row: a batch's
   * ~8,000 single-row inserts were ~25% of its time, almost all of it round trips rather than work
   * in PostgreSQL (row 26p, TASK-LOG.md step 112). Each row's {@code ON CONFLICT} still applies on
   * its own, so a rerun skips exactly the rows that already landed.
   */
  @Override
  public void recordAll(List<AllotmentRecord> allotments) {
    for (int from = 0; from < allotments.size(); from += ROWS_PER_STATEMENT) {
      insertChunk(allotments.subList(from, Math.min(allotments.size(), from + ROWS_PER_STATEMENT)));
    }
  }

  private void insertChunk(List<AllotmentRecord> chunk) {
    StringBuilder sql =
        new StringBuilder(
            """
            INSERT INTO workflow.allotment (
                organization_id, batch_id, participant_internal_ref, source, fund_id,
                nav_version_id, units_scaled, vested, transfer_id, allotted_at)
            VALUES
            """);
    Map<String, Object> params = new HashMap<>();
    for (int i = 0; i < chunk.size(); i++) {
      AllotmentRecord allotment = chunk.get(i);
      if (i > 0) {
        sql.append(",\n");
      }
      sql.append(
          String.format(
              Locale.ROOT,
              "(:organizationId%1$d, :batchId%1$d, :participantInternalRef%1$d, :source%1$d,"
                  + " :fundId%1$d, :navVersionId%1$d, :unitsScaled%1$d, :vested%1$d,"
                  + " :transferId%1$d, :allottedAt%1$d)",
              i));
      params.put("organizationId" + i, allotment.organizationId().value());
      params.put("batchId" + i, allotment.batchId());
      params.put("participantInternalRef" + i, allotment.participantInternalRef());
      params.put("source" + i, allotment.source().name());
      params.put("fundId" + i, allotment.fundId().value());
      params.put("navVersionId" + i, allotment.navVersionId());
      params.put("unitsScaled" + i, allotment.units().scaled());
      params.put("vested" + i, allotment.vested());
      params.put("transferId" + i, TransferIdDeriver.toUuid(allotment.transferId()));
      params.put("allottedAt" + i, Timestamp.from(allotment.allottedAt()));
    }
    sql.append("\nON CONFLICT ON CONSTRAINT allotment_pk DO NOTHING");
    jdbc.sql(sql.toString()).params(params).update();
  }
}
