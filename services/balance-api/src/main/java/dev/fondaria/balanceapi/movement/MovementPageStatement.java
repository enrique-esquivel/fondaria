package dev.fondaria.balanceapi.movement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The SQL and the parameters for one page of movement history — built here rather than inside the
 * repository so the integration test can put {@code EXPLAIN} in front of the exact statement the
 * service runs, instead of a hand-written approximation of it.
 *
 * <h2>Why a UNION of one branch per account and leg</h2>
 *
 * <p>A movement is a leg, and {@code projection.transfer} carries the two legs as two columns. The
 * obvious query — {@code WHERE debit_account_id = ANY(…) OR credit_account_id = ANY(…)} — cannot be
 * served by an ordered index scan: an {@code OR} across two columns needs a bitmap, and a bitmap
 * loses the ordering keyset pagination depends on, so every page would sort the participant's whole
 * history. Instead each {@code (account, leg)} pair is its own branch with an equality predicate,
 * its own {@code ORDER BY ledger_timestamp DESC, transfer_id DESC} and <b>its own {@code LIMIT}</b>
 * — served directly by {@code transfer_by_debit_account} / {@code transfer_by_credit_account}
 * (V23). The per-branch limit is what makes the cost of a page the page: whatever the planner
 * chooses to do above them, no branch can hand up more rows than one page could need.
 *
 * <p>The branch count is <b>{@code 4 × funds}</b> — two holding accounts per fund, each of which
 * can appear on either side of a transfer. It is never a function of how many participants exist,
 * which is the property that matters; it <i>is</i> a function of how many funds the registry holds,
 * which v1 keeps to a handful (`ADR-0012`). If that ever stops being true the candidate set has to
 * be narrowed to the funds a participant has actually held — see `TASK-LOG.md` step 33 for why that
 * is not a one-line change today.
 *
 * <p><b>The per-branch limit is only sound because {@code leg} is constant within a branch.</b>
 * Each branch orders by {@code (ledger_timestamp DESC, transfer_id DESC)}, which is consistent with
 * the outer {@code (…, leg ASC)} precisely because every row in a branch carries the same leg. A
 * row in the global top-K is then also in its own branch's top-K, so the outer {@code LIMIT} is
 * exact rather than lossy. A change that let {@code leg} vary within a branch would break that
 * argument and silently drop rows.
 *
 * <h2>Why the cursor predicate is {@code <=} and not {@code <}</h2>
 *
 * <p>The keyset is {@code (ledger_timestamp, transfer_id)} descending, but the ordering is total
 * only with the leg (see {@link MovementCursor}) — and the leg is a property of the branch, not a
 * column to compare. Rather than write a three-part row comparison that differs per branch, the SQL
 * takes everything at or below the cursor's transfer and {@link MovementService} drops what is not
 * strictly after it. At most two rows share a {@code (timestamp, transfer_id)}, so at most two rows
 * are ever discarded, which is why the caller asks for a few more than a page.
 */
record MovementPageStatement(String sql, Map<String, Object> parameters) {

  MovementPageStatement {
    Objects.requireNonNull(sql, "sql must not be null");
    parameters = Map.copyOf(parameters);
  }

  /**
   * @param accountIds the participant's holding accounts, unsigned decimal u128 — two per fund in
   *     scope
   * @param rowLimit rows per branch and overall; the caller asks for more than a page so the
   *     cursor's own row and the "is there more" probe both fit
   */
  static MovementPageStatement build(
      String tenantId,
      List<String> accountIds,
      MovementQuery query,
      Optional<MovementCursor> cursor,
      int rowLimit) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(accountIds, "accountIds must not be null");
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(cursor, "cursor must not be null");
    if (accountIds.isEmpty()) {
      throw new IllegalArgumentException("at least one holding account is required");
    }

    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("tenantId", tenantId);
    parameters.put("rowLimit", rowLimit);
    query.from().ifPresent(from -> parameters.put("from", from));
    query.to().ifPresent(to -> parameters.put("to", to));
    query.transferCode().ifPresent(code -> parameters.put("code", code.ledgerCode()));
    cursor.ifPresent(
        position -> {
          parameters.put("cursorTimestamp", position.ledgerTimestamp().toString());
          parameters.put("cursorTransferId", position.transferId().toString());
        });

    StringBuilder sql = new StringBuilder("SELECT * FROM (\n");
    boolean first = true;
    for (int i = 0; i < accountIds.size(); i++) {
      parameters.put(accountParameter(i), accountIds.get(i));
      for (MovementDirection direction : MovementDirection.values()) {
        if (!first) {
          sql.append("\n  UNION ALL\n");
        }
        first = false;
        sql.append(branch(i, direction, query, cursor.isPresent()));
      }
    }
    sql.append("\n) AS movement\n")
        .append("ORDER BY movement.ledger_timestamp DESC, movement.transfer_id DESC,")
        .append(" movement.leg ASC\n")
        .append("LIMIT :rowLimit");

    return new MovementPageStatement(sql.toString(), parameters);
  }

  private static String branch(
      int accountIndex, MovementDirection direction, MovementQuery query, boolean hasCursor) {
    String column = direction == MovementDirection.DEBIT ? "debit_account_id" : "credit_account_id";
    StringBuilder branch = new StringBuilder();
    branch
        .append("  (SELECT t.transfer_id, t.ledger_timestamp, t.")
        .append(column)
        .append(" AS account_id, ")
        .append(direction.leg())
        .append(" AS leg,\n")
        .append(
            "          t.amount_scaled, t.code, t.booking_date, t.value_date, t.nav_version_id\n")
        .append("     FROM projection.transfer t\n")
        .append("    WHERE t.tenant_id = :tenantId\n")
        .append("      AND t.")
        .append(column)
        .append(" = CAST(:")
        .append(accountParameter(accountIndex))
        .append(" AS numeric)\n");
    if (query.from().isPresent()) {
      branch.append("      AND t.value_date >= :from\n");
    }
    if (query.to().isPresent()) {
      branch.append("      AND t.value_date <= :to\n");
    }
    if (query.transferCode().isPresent()) {
      branch.append("      AND t.code = :code\n");
    }
    if (hasCursor) {
      branch
          .append("      AND (t.ledger_timestamp, t.transfer_id)")
          .append(" <= (CAST(:cursorTimestamp AS numeric), CAST(:cursorTransferId AS numeric))\n");
    }
    branch
        .append("    ORDER BY t.ledger_timestamp DESC, t.transfer_id DESC\n")
        .append("    LIMIT :rowLimit)");
    return branch.toString();
  }

  private static String accountParameter(int index) {
    return "account" + index;
  }
}
