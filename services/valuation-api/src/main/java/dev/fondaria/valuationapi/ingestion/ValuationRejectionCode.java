package dev.fondaria.valuationapi.ingestion;

/**
 * The whole-batch rejection vocabulary of {@code valuation-file-v1.md} §4, in the order the checks
 * run — cheapest first.
 *
 * <p>Every code here rejects the <b>whole file</b>. A partially accepted valuation file would leave
 * some funds priced on this delivery and some not, with nothing recording which — and since a NAV
 * is what turns units into a balance (docs/ENGINEERING-RULES.md §2), "some funds are stale and we
 * do not know which" is the worst available outcome.
 */
public enum ValuationRejectionCode {
  /** The stored object's SHA-256 is not the checksum that was submitted, or nothing is stored. */
  CHECKSUM_MISMATCH,

  /** The file does not parse: bad framing, a missing column, an ill-typed field. */
  MALFORMED_FILE,

  /**
   * A {@code reportedValueCents} carries a decimal point.
   *
   * <p>Its own code rather than {@link #MALFORMED_FILE} because it is the one parse failure whose
   * tempting recovery is wrong. A provider sending {@code 1234.56} means MXN 1,234.56, and a reader
   * that rounds is right nine times out of ten and catastrophically wrong the tenth — on a number
   * that moves every participant's balance in the fund. Naming it tells the provider exactly what
   * to change.
   */
  DECIMAL_AMOUNT,

  /** The submitted row count is not the number of valuation rows the file contains. */
  ROW_COUNT_MISMATCH,

  /** Two rows share a {@code (fundId, valuationDate)}. One fund is valued once per date. */
  DUPLICATE_ROW,

  /** A valuation date has not happened yet. You cannot value a fund on a future date. */
  FUTURE_VALUATION_DATE,

  /** A valuation date is a weekend or a holiday — not a date the fund is valued on (Task 7.1). */
  NON_BUSINESS_DATE,

  /** A fund id does not resolve in {@code reference.fund} as of its own valuation date. */
  UNKNOWN_FUND,

  /**
   * The file contradicts a valuation already accepted for that fund and date.
   *
   * <p><b>Not an overwrite, and never becomes one.</b> This is a correction, and corrections go
   * through Task 20.5 and nowhere else: a correction supersedes a NAV that allotments may already
   * have been struck against, so it needs a reason, an audit trail and (from Phase 9) four eyes.
   * Letting a redelivered file silently reprice a fund would make all of that optional.
   *
   * <p>An <i>identical</i> redelivery is not this. It is an idempotent no-op and the batch is
   * accepted.
   */
  VALUATION_ALREADY_LIVE
}
