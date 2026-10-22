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
 * {@link CustodianBreakRepository} over {@code workflow.custodian_break} ({@code V27}), using
 * {@code JdbcClient} (AGENTS.md §5 — no ORM).
 *
 * <p>Unlike {@code workflow.reconciliation_break} (Task 7.2), rows here are updated in place —
 * {@code workflow}'s ordinary grant, no extra revoke or trigger, because the task requires a break
 * be assigned and investigated, which is a status a human changes.
 */
@Repository
public class JdbcCustodianBreakRepository implements CustodianBreakRepository {

  private final JdbcClient jdbc;

  public JdbcCustodianBreakRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<CustodianBreak> findOpen(UUID providerAccountId, BreakCategory category) {
    return jdbc.sql(
            """
            SELECT break_id, provider_account_id, category, status, drift_scaled,
                   first_detected_business_date, last_confirmed_business_date, assigned_to,
                   opened_at, status_updated_at, escalated_at, resolved_at, resolution_note
              FROM workflow.custodian_break
             WHERE provider_account_id = :providerAccountId
               AND category = :category
               AND status <> 'RESOLVED'
            """)
        .param("providerAccountId", providerAccountId)
        .param("category", category.name())
        .query(JdbcCustodianBreakRepository::mapBreak)
        .optional();
  }

  @Override
  public CustodianBreak open(
      UUID providerAccountId,
      BreakCategory category,
      long driftScaled,
      LocalDate businessDate,
      Instant now) {
    UUID breakId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_break (
                break_id, provider_account_id, category, status, drift_scaled,
                first_detected_business_date, last_confirmed_business_date,
                opened_at, status_updated_at)
            VALUES (
                :breakId, :providerAccountId, :category, 'OPEN', :drift,
                :businessDate, :businessDate, :now, :now)
            """)
        .param("breakId", breakId)
        .param("providerAccountId", providerAccountId)
        .param("category", category.name())
        .param("drift", driftScaled)
        .param("businessDate", businessDate)
        .param("now", Timestamp.from(now))
        .update();
    return new CustodianBreak(
        breakId,
        providerAccountId,
        category,
        BreakStatus.OPEN,
        driftScaled,
        businessDate,
        businessDate,
        Optional.empty(),
        now,
        now,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public void confirmStillOpen(
      UUID breakId, long driftScaled, LocalDate businessDate, Instant now) {
    // GREATEST guards against a pass run for a business date earlier than the one already
    // recorded -- a replayed or accelerated demo clock, or clock skew between instances -- which
    // would otherwise violate custodian_break_dates_ordered (V27) and fail the whole pass.
    jdbc.sql(
            """
            UPDATE workflow.custodian_break
               SET drift_scaled = :drift,
                   last_confirmed_business_date = GREATEST(last_confirmed_business_date, :businessDate),
                   status_updated_at = :now
             WHERE break_id = :breakId
               AND status <> 'RESOLVED'
            """)
        .param("drift", driftScaled)
        .param("businessDate", businessDate)
        .param("now", Timestamp.from(now))
        .param("breakId", breakId)
        .update();
  }

  @Override
  public void resolve(UUID breakId, Instant now, String resolutionNote) {
    jdbc.sql(
            """
            UPDATE workflow.custodian_break
               SET status = 'RESOLVED',
                   resolved_at = :now,
                   status_updated_at = :now,
                   resolution_note = :note
             WHERE break_id = :breakId
               AND status <> 'RESOLVED'
            """)
        .param("now", Timestamp.from(now))
        .param("note", resolutionNote)
        .param("breakId", breakId)
        .update();
  }

  @Override
  public void assign(UUID breakId, String assignedTo, Instant now) {
    jdbc.sql(
            """
            UPDATE workflow.custodian_break
               SET status = 'ASSIGNED', assigned_to = :assignedTo, status_updated_at = :now
             WHERE break_id = :breakId
               AND status <> 'RESOLVED'
            """)
        .param("assignedTo", assignedTo)
        .param("now", Timestamp.from(now))
        .param("breakId", breakId)
        .update();
  }

  @Override
  public void investigate(UUID breakId, Instant now) {
    jdbc.sql(
            """
            UPDATE workflow.custodian_break
               SET status = 'INVESTIGATING', status_updated_at = :now
             WHERE break_id = :breakId
               AND status <> 'RESOLVED'
            """)
        .param("now", Timestamp.from(now))
        .param("breakId", breakId)
        .update();
  }

  @Override
  public int escalateAged(
      LocalDate businessDate, int realBreakThresholdDays, int timingThresholdDays, Instant now) {
    return jdbc.sql(
            """
            UPDATE workflow.custodian_break
               SET status = 'ESCALATED', escalated_at = :now, status_updated_at = :now
             WHERE status IN ('OPEN', 'ASSIGNED', 'INVESTIGATING')
               AND (
                     (category IN ('UNIT_BREAK', 'CASH_BREAK')
                      AND (:businessDate - first_detected_business_date) >= :realThreshold)
                  OR (category IN ('UNIT_TIMING_DIFFERENCE', 'CASH_TIMING_DIFFERENCE')
                      AND (:businessDate - first_detected_business_date) >= :timingThreshold)
                   )
            """)
        .param("now", Timestamp.from(now))
        .param("businessDate", businessDate)
        .param("realThreshold", realBreakThresholdDays)
        .param("timingThreshold", timingThresholdDays)
        .update();
  }

  @Override
  public boolean hasOpenRealBreak() {
    Boolean exists =
        jdbc.sql(
                """
                SELECT EXISTS (
                  SELECT 1 FROM workflow.custodian_break
                   WHERE status <> 'RESOLVED'
                     AND category IN ('UNIT_BREAK', 'CASH_BREAK')
                )
                """)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  private static CustodianBreak mapBreak(ResultSet rs, int rowNum) throws SQLException {
    Timestamp escalatedAt = rs.getTimestamp("escalated_at");
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    String assignedTo = rs.getString("assigned_to");
    String resolutionNote = rs.getString("resolution_note");
    return new CustodianBreak(
        rs.getObject("break_id", UUID.class),
        rs.getObject("provider_account_id", UUID.class),
        BreakCategory.valueOf(rs.getString("category")),
        BreakStatus.valueOf(rs.getString("status")),
        rs.getLong("drift_scaled"),
        rs.getObject("first_detected_business_date", LocalDate.class),
        rs.getObject("last_confirmed_business_date", LocalDate.class),
        Optional.ofNullable(assignedTo),
        rs.getTimestamp("opened_at").toInstant(),
        rs.getTimestamp("status_updated_at").toInstant(),
        Optional.ofNullable(escalatedAt).map(Timestamp::toInstant),
        Optional.ofNullable(resolvedAt).map(Timestamp::toInstant),
        Optional.ofNullable(resolutionNote));
  }
}
