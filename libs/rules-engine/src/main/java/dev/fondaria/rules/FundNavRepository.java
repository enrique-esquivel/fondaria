package dev.fondaria.rules;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Storage for {@code reference.fund_nav} — the struck NAV per fund per valuation date (Task 20.4).
 *
 * <p>An interface for the same reason {@link FundRepository} is one: the strike's business logic
 * should be provable without a database in the loop. The one JDBC implementation lives in
 * valuation-api, the service that writes this table.
 */
public interface FundNavRepository {

  /**
   * The original (non-superseding) strike for {@code fundId} on {@code valuationDate}, if one has
   * been recorded — the row the unique index on {@code (fund_id, valuation_date) WHERE
   * supersedes_id IS NULL} guarantees is the only one of its kind.
   */
  Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate);

  /**
   * Records a new, original strike. {@code candidate.supersedesId()} must be null — a correction
   * goes through {@link #correct}, not this method.
   *
   * <p>Idempotent on {@code (fundId, valuationDate)}: if an original strike already exists for that
   * pair, the existing row is returned unchanged and {@code candidate} is discarded rather than
   * recomputed or overwritten — the first strike for a date is authoritative regardless of how
   * units in issue may have moved by the time a second call arrives.
   */
  FundNav strike(FundNav candidate);

  /**
   * The live row for {@code fundId} on {@code valuationDate} specifically — the row nothing
   * supersedes, whether or not that date is the fund's most recent (Task 20.5: a correction can
   * target any date in the series, not only the latest one).
   *
   * <p>Same "live" rule as {@link #findLatestLive}, pinned to one date instead of ranging over
   * every date the fund has ever struck.
   */
  Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate);

  /**
   * Records a correction: a new row with {@code candidate.supersedesId()} set to the {@code
   * nav_version_id} it corrects. Requires {@code candidate.reason()} non-blank, which {@link
   * FundNav}'s own constructor already enforces whenever {@code supersedesId} is set.
   *
   * <p><b>Atomic on whether the row it names is still live</b> — the same reason {@link #strike}
   * conflicts on the unique index instead of trusting a candidate's own claim: two concurrent
   * corrections must not both succeed against the same still-live row, which would leave two rows
   * nothing else supersedes and no way to say which one is authoritative. Implementations must
   * reject a candidate whose {@code supersedesId} does not identify a row that exists, matches
   * {@code candidate.fundId()} and {@code candidate.valuationDate()}, and is still live at the
   * moment of the write.
   *
   * <p><b>Never rewrites {@code workflow.allotment}.</b> Every allotment already made under the
   * superseded price keeps naming it, unchanged — re-allotment against a corrected NAV is a Phase
   * 14 concern, not a side effect of this method (`docs/ENGINEERING-RULES.md` §4, Task 20.5's own
   * requirements).
   *
   * @throws IllegalArgumentException {@code candidate.supersedesId()} is null
   * @throws IllegalStateException the row {@code candidate.supersedesId()} names does not exist,
   *     does not match {@code candidate}'s fund and date, or has already been superseded
   */
  FundNav correct(FundNav candidate);

  /**
   * The <b>live</b> NAV for {@code fundId} on its most recent valuation date — the price a balance
   * read should multiply by today (Task 20.6).
   *
   * <p><b>"Live" means the row nothing supersedes, which is not the same as {@code supersedes_id IS
   * NULL}.</b> {@code supersedes_id} is carried by the <i>correction</i> and points back at the row
   * it replaces (Task 20.5); the original strike is the one holding null. Filtering on {@code
   * supersedes_id IS NULL} therefore selects the original and would keep serving a price that has
   * already been corrected — which is exactly the failure a correction exists to prevent. The
   * partial unique index of the same shape guarantees one <i>original</i> per fund and date; it
   * does not identify the live one.
   */
  Optional<FundNav> findLatestLive(FundId fundId);

  /**
   * One live NAV per fund that has ever been struck — the whole cache in a single query (Task
   * 20.6). Same "live" rule as {@link #findLatestLive}. Funds with no NAV yet are absent rather
   * than present with a zero, which is a different and misleading claim.
   */
  List<FundNav> findLatestLivePerFund();
}
