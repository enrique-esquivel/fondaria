package dev.fondaria.balanceapi.asof;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The SQL and the parameters for "what did each of these holding accounts hold on this date" —
 * built here rather than inside the repository so the integration test can put {@code EXPLAIN} in
 * front of the exact statement the service runs, the same arrangement {@code MovementPageStatement}
 * uses for the movement page.
 *
 * <h2>Why a UNION of one branch per account</h2>
 *
 * <p>The question is per account: <i>the latest row at or before the date</i>. That is a {@code
 * LIMIT 1} over a descending range, and no single predicate answers it for several accounts at once
 * without either a window function over every one of their rows or a correlated subquery per
 * account. One branch per account, each with its own {@code ORDER BY effective_date DESC LIMIT 1},
 * is the form the primary key can serve directly: {@code (tenant_id, participant_id, fund_id,
 * effective_date)} is exactly this branch's equality predicates followed by its sort column, so
 * each branch is a backward index scan that stops after one row — independent of how many rows the
 * table holds, which is what "under 10 ms at 10 million rows" actually requires.
 *
 * <p><b>{@code fund_id} is in the predicate even though the account id already determines it.</b> A
 * holding account belongs to exactly one fund, so the extra equality excludes nothing. It is there
 * to complete the primary key's prefix: without it {@code effective_date} sits on the far side of
 * an unconstrained key column and the planner can no longer walk the index in sorted order — it
 * reads every row for the account and sorts them. Correct either way; the difference is whether the
 * cost is bounded.
 *
 * <p><b>{@code fund_id} is bound uncast, and deliberately.</b> It arrives as {@code
 * reference.fund_identity.unit_ledger_id}, a {@code bigint}, while {@code
 * projection.participant_units.fund_id} is an {@code integer} (V20 and V10 disagree on the width of
 * the same number). PostgreSQL compares {@code int4 = int8} through the integer operator family's
 * cross-type members, so the index still serves it, and a ledger id too large for {@code integer}
 * simply matches nothing — which is the truth, since the projection could never have stored a row
 * for it. Casting the parameter down instead raises {@code integer out of range} and fails a
 * request for a participant who holds no such fund, which a leftover wide-id fund in a shared test
 * database demonstrated immediately.
 *
 * <p>The branch count is {@code 2 × funds} — two holding accounts per fund — and never a function
 * of how many participants exist, which is the property this phase is about.
 */
record HistoricUnitsStatement(String sql, Map<String, Object> parameters) {

  HistoricUnitsStatement {
    Objects.requireNonNull(sql, "sql must not be null");
    parameters = Map.copyOf(parameters);
  }

  /**
   * @param tenantId the partition key — the organization's {@code TenantRef} as unsigned decimal
   * @param accounts the participant's holding accounts and the unit ledger each belongs to
   * @param asOfDate the date the holding is asked for; every branch reads at or before it
   */
  static HistoricUnitsStatement build(
      String tenantId, List<HoldingAccountKey> accounts, LocalDate asOfDate) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(accounts, "accounts must not be null");
    Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    if (accounts.isEmpty()) {
      throw new IllegalArgumentException("at least one holding account is required");
    }

    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("tenantId", tenantId);
    parameters.put("asOfDate", asOfDate);

    StringBuilder sql = new StringBuilder();
    for (int i = 0; i < accounts.size(); i++) {
      HoldingAccountKey account = accounts.get(i);
      parameters.put(accountParameter(i), account.accountId());
      parameters.put(fundParameter(i), account.unitLedgerId());
      if (i > 0) {
        sql.append("\nUNION ALL\n");
      }
      sql.append(branch(i));
    }
    return new HistoricUnitsStatement(sql.toString(), parameters);
  }

  private static String branch(int index) {
    return "(SELECT u.participant_id AS account_id, u.fund_id, u.effective_date, u.units_scaled\n"
        + "   FROM projection.participant_units u\n"
        + "  WHERE u.tenant_id = :tenantId\n"
        + "    AND u.participant_id = CAST(:"
        + accountParameter(index)
        + " AS numeric)\n"
        + "    AND u.fund_id = :"
        + fundParameter(index)
        + "\n"
        + "    AND u.effective_date <= :asOfDate\n"
        + "  ORDER BY u.effective_date DESC\n"
        + "  LIMIT 1)";
  }

  private static String accountParameter(int index) {
    return "account" + index;
  }

  private static String fundParameter(int index) {
    return "fund" + index;
  }
}
