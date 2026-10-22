package dev.fondaria.balanceapi.movement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link MovementRepository} over {@code projection.transfer}, using {@code JdbcClient} (AGENTS.md
 * §5 — no ORM).
 *
 * <p>The statement itself is built by {@link MovementPageStatement}, which carries the reasoning
 * about branches, indexes and the cursor predicate. This class is the execution and the row
 * mapping, and nothing else — in particular it interprets no account id and computes no total.
 *
 * <p><b>Read-only against a CDC-fed schema</b> (ADR-0011): {@code projection} is never written by a
 * service, and this one holds no {@code INSERT} or {@code UPDATE} against it at all.
 */
@Repository
public class JdbcMovementRepository implements MovementRepository {

  private final JdbcClient jdbc;

  public JdbcMovementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<MovementLeg> page(
      String tenantId,
      List<String> accountIds,
      MovementQuery query,
      Optional<MovementCursor> cursor,
      int rowLimit) {
    MovementPageStatement statement =
        MovementPageStatement.build(tenantId, accountIds, query, cursor, rowLimit);
    JdbcClient.StatementSpec spec = jdbc.sql(statement.sql());
    for (var parameter : statement.parameters().entrySet()) {
      spec = spec.param(parameter.getKey(), parameter.getValue());
    }
    return spec.query(JdbcMovementRepository::mapLeg).list();
  }

  private static MovementLeg mapLeg(ResultSet rs, int rowNum) throws SQLException {
    // wasNull() reports on the most recent getter, so the nullable column is read first and
    // its nullity captured immediately — reading it last would report on booking_date instead.
    long navVersionScalar = rs.getLong("nav_version_id");
    Optional<Long> navVersionId = rs.wasNull() ? Optional.empty() : Optional.of(navVersionScalar);
    return new MovementLeg(
        // getString, not getBigDecimal: these are u128/u64 *identifiers* stored as numeric(_,0),
        // and PostgreSQL already renders them in the canonical unsigned decimal the rest of the
        // platform uses. Going through BigDecimal would put a decimal type in a file next to money
        // for no gain (AGENTS.md §4).
        rs.getString("transfer_id"),
        rs.getString("ledger_timestamp"),
        rs.getString("account_id"),
        MovementDirection.ofLeg(rs.getInt("leg")),
        rs.getLong("amount_scaled"),
        rs.getInt("code"),
        rs.getObject("booking_date", LocalDate.class),
        rs.getObject("value_date", LocalDate.class),
        navVersionId);
  }
}
