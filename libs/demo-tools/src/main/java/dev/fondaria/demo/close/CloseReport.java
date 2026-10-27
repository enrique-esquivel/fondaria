package dev.fondaria.demo.close;

import dev.fondaria.demo.Display;
import java.util.ArrayList;
import java.util.List;

/**
 * One signed-off close as its own audit rows record it: {@code workflow.close_signoff} and the
 * three runs it names. Built from {@link #QUERY}'s single row, so what this prints is exactly the
 * evidence the sign-off cites -- not a re-derivation, and not a figure this module computed.
 *
 * <p>Counts, statuses, ids, object keys and hashes only: no balance, no name, no RFC.
 */
record CloseReport(
    String businessDate,
    String signedOffAt,
    boolean announced,
    Run reconciliation,
    int fundCount,
    long holdingAccountCount,
    int cashAccountCount,
    int breakCount,
    long reconciliationMillis,
    Run custodian,
    int providerAccountCount,
    int missingStatementCount,
    int realBreakCount,
    Run snapshot,
    long positionCount,
    int snapshotFundCount,
    String positionsObjectKey,
    String manifestObjectKey,
    String manifestSha256,
    long reconciliationPasses,
    long reconciliationFailures) {

  record Run(String runId, String status) {}

  /** Every column {@link #fromRow} reads, in order. {@code %s} is the business date. */
  static final String QUERY =
      """
      SELECT s.business_date,
             to_char(s.signed_off_at AT TIME ZONE 'America/Mexico_City', 'YYYY-MM-DD HH24:MI:SS'),
             s.announced_at IS NOT NULL,
             r.run_id, r.status, r.fund_count, r.holding_account_count, r.cash_account_count,
             r.break_count, coalesce(r.duration_millis, 0),
             c.run_id, c.status, c.provider_account_count, c.missing_statement_count,
             c.real_break_count,
             n.run_id, n.status, n.position_count, n.fund_count, n.positions_object_key,
             n.manifest_object_key, n.manifest_sha256,
             (SELECT count(*) FROM workflow.reconciliation_run x
               WHERE x.business_date = s.business_date),
             (SELECT count(*) FROM workflow.reconciliation_run x
               WHERE x.business_date = s.business_date AND x.status = 'FAILED')
        FROM workflow.close_signoff s
        JOIN workflow.reconciliation_run r ON r.run_id = s.reconciliation_run_id
        JOIN workflow.custodian_reconciliation_run c ON c.run_id = s.custodian_run_id
        JOIN workflow.snapshot_run n ON n.run_id = s.snapshot_run_id
       WHERE s.business_date = DATE '%s'
      """;

  static CloseReport fromRow(List<String> row) {
    if (row.size() != 24) {
      throw new IllegalArgumentException("expected 24 columns, got " + row.size());
    }
    return new CloseReport(
        row.get(0),
        row.get(1),
        row.get(2).equals("t"),
        new Run(row.get(3), row.get(4)),
        Integer.parseInt(row.get(5)),
        Long.parseLong(row.get(6)),
        Integer.parseInt(row.get(7)),
        Integer.parseInt(row.get(8)),
        Long.parseLong(row.get(9)),
        new Run(row.get(10), row.get(11)),
        Integer.parseInt(row.get(12)),
        Integer.parseInt(row.get(13)),
        Integer.parseInt(row.get(14)),
        new Run(row.get(15), row.get(16)),
        Long.parseLong(row.get(17)),
        Integer.parseInt(row.get(18)),
        row.get(19),
        row.get(20),
        row.get(21),
        Long.parseLong(row.get(22)),
        Long.parseLong(row.get(23)));
  }

  /** The report, one line per element, ready to print. */
  List<String> render(String snapshotBucket, HashChainCheck chain) {
    List<String> lines = new ArrayList<>();
    lines.add(
        "Daily close "
            + businessDate
            + ": SIGNED OFF at "
            + signedOffAt
            + " (simulated, America/Mexico_City); close.signed-off.v1 "
            + (announced ? "published" : "NOT YET published -- the next sign-off sweep retries"));
    lines.add(
        String.format(
            "  Internal reconciliation   %-9s run %s: %s funds, %s holding accounts, %s cash"
                + " accounts, %s breaks, %.1f s",
            reconciliation.status(),
            reconciliation.runId(),
            Display.count(fundCount),
            Display.count(holdingAccountCount),
            Display.count(cashAccountCount),
            Display.count(breakCount),
            reconciliationMillis / 1000.0));
    if (reconciliationPasses > 1) {
      lines.add(
          "                            "
              + Display.count(reconciliationPasses)
              + " passes for this date"
              + (reconciliationFailures > 0
                  ? "; "
                      + Display.count(reconciliationFailures)
                      + " did not tie while the book was still settling, and blocked the close"
                      + " until a later pass did"
                  : ""));
    }
    lines.add(
        String.format(
            "  Custodian reconciliation  %-9s run %s: %s provider accounts, %s missing statements,"
                + " %s real breaks",
            custodian.status(),
            custodian.runId(),
            Display.count(providerAccountCount),
            Display.count(missingStatementCount),
            Display.count(realBreakCount)));
    lines.add(
        String.format(
            "  Dense snapshot            %-9s run %s: %s positions across %s funds",
            snapshot.status(),
            snapshot.runId(),
            Display.count(positionCount),
            Display.count(snapshotFundCount)));
    lines.add("                            s3://" + snapshotBucket + "/" + positionsObjectKey);
    lines.add(
        "                            s3://"
            + snapshotBucket
            + "/"
            + manifestObjectKey
            + "  (manifest sha256 "
            + Display.shortHash(manifestSha256)
            + ")");
    lines.add("  Hash chain                " + chain.describe());
    return lines;
  }
}
