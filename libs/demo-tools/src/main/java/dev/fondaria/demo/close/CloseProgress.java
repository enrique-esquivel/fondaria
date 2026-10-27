package dev.fondaria.demo.close;

import java.util.List;

/**
 * Where one business date's close stands while {@code close} waits for it: the latest settled
 * internal reconciliation, the latest snapshot run, and whether the sign-off row exists and is
 * announced. Printed only when it changes, so a wait reads as a short story rather than a poll log.
 *
 * @param reconciliation latest settled run's status and break count ({@code PASSED 0}), or empty
 * @param snapshot latest snapshot run's status, or empty
 */
record CloseProgress(String reconciliation, String snapshot, boolean signedOff, boolean announced) {

  /** {@code %1$s} is the business date. */
  static final String QUERY =
      """
      SELECT coalesce((SELECT status || ' ' || break_count FROM workflow.reconciliation_run
                        WHERE business_date = DATE '%1$s' AND status <> 'RUNNING'
                        ORDER BY completed_at DESC LIMIT 1), ''),
             coalesce((SELECT status FROM workflow.snapshot_run
                        WHERE business_date = DATE '%1$s'
                        ORDER BY started_at DESC LIMIT 1), ''),
             EXISTS (SELECT 1 FROM workflow.close_signoff WHERE business_date = DATE '%1$s'),
             EXISTS (SELECT 1 FROM workflow.close_signoff
                      WHERE business_date = DATE '%1$s' AND announced_at IS NOT NULL)
      """;

  static CloseProgress fromRow(List<String> row) {
    if (row.size() != 4) {
      throw new IllegalArgumentException("expected 4 columns, got " + row.size());
    }
    return new CloseProgress(
        row.get(0), row.get(1), row.get(2).equals("t"), row.get(3).equals("t"));
  }

  /** A one-line description for the operator. */
  String describe() {
    if (signedOff) {
      return announced
          ? "signed off; close.signed-off.v1 published"
          : "signed off; publishing close.signed-off.v1";
    }
    if (reconciliation.isEmpty()) {
      return "waiting for the first internal reconciliation pass";
    }
    String[] parts = reconciliation.split(" ");
    if (parts[0].equals("FAILED")) {
      return "internal reconciliation found "
          + parts[1]
          + " break(s) -- usually the projection still catching up with the day's allotments;"
          + " the next pass retries";
    }
    if (snapshot.isEmpty()) {
      return "internal reconciliation passed; waiting for the dense snapshot";
    }
    return "internal reconciliation passed; snapshot " + snapshot + "; waiting for sign-off";
  }
}
