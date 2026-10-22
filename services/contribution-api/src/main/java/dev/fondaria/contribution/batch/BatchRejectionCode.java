package dev.fondaria.contribution.batch;

/**
 * The whole-batch rejection vocabulary of {@code contribution-file-v2.md} §4, in the order the
 * checks run — cheapest first — plus the two plan-rule codes step 5 (BACKLOG.md) adds after them.
 *
 * <p>Per-row (plan-rule) rejection is Task 3.5's contract and is not expressed here: both {@link
 * #PLAN_VERSION_NOT_FOUND} and {@link #EMPLOYER_MATCH_RATIO_BREACH} are whole-batch, same as every
 * code above them. A batch carrying one of these codes staged nothing, enrolled nobody and allotted
 * nothing: whole-batch rejection is what AGENTS.md §1's "never a partial allotment" means in
 * practice.
 */
public enum BatchRejectionCode {
  /** The stored object's SHA-256 is not the checksum that was submitted, or nothing is stored. */
  CHECKSUM_MISMATCH,
  /**
   * The file does not parse: bad framing, a missing or ill-typed column, a row contributing
   * nothing, or a participant enrolled before being employed.
   */
  MALFORMED_FILE,
  /** The submitted row count is not the number of participant rows the file contains. */
  ROW_COUNT_MISMATCH,
  /** Two participant rows share a participant. Each participant has exactly one row. */
  DUPLICATE_ROW,
  /** A declared source total is not the sum of that source's column in the file. */
  SOURCE_SUBTOTAL_MISMATCH,
  /** The declared source totals do not sum to the declared grand total. */
  DECLARED_TOTAL_MISMATCH,
  /** No {@code reference.plan_version} resolves for the declared plan on the batch's value date. */
  PLAN_VERSION_NOT_FOUND,
  /** At least one row's employer amount does not match the plan's employer matching ratio. */
  EMPLOYER_MATCH_RATIO_BREACH
}
