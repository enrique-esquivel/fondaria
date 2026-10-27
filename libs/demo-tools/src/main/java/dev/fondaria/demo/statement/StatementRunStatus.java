package dev.fondaria.demo.statement;

import java.util.List;

/**
 * One {@code workflow.statement_run} row: a month's fan-out and whether every expected statement
 * has been stored.
 */
record StatementRunStatus(
    String status, long expectedCount, String completedCount, String closeBusinessDate) {

  /** {@code %s} is the period, {@code YYYY-MM}. */
  static final String QUERY =
      """
      SELECT status, expected_count, coalesce(completed_count::text, ''), close_business_date
        FROM workflow.statement_run
       WHERE period = '%s'
      """;

  static StatementRunStatus fromRow(List<String> row) {
    if (row.size() != 4) {
      throw new IllegalArgumentException("expected 4 columns, got " + row.size());
    }
    return new StatementRunStatus(row.get(0), Long.parseLong(row.get(1)), row.get(2), row.get(3));
  }

  boolean completed() {
    return status.equals("COMPLETED");
  }

  boolean failed() {
    return status.equals("FAILED");
  }
}
