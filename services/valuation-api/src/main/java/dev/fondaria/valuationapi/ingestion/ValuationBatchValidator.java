package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.valuationapi.fund.FundDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Checks 4 to 9 of {@code valuation-file-v1.md} §4, in order: row count, duplicate rows, future
 * dates, non-business dates, unknown funds, and contradictions with what is already accepted.
 *
 * <p>Checks 1 to 3 (checksum, parse, decimal amount) happen before this — the first in {@link
 * ValuationIngestionRunner}, the other two in {@link ValuationFileParser} — because they need only
 * the bytes.
 *
 * <p><b>The order is the contract, not an optimisation.</b> A truncated file is caught by the row
 * count before any of its dates or funds are examined, so the provider is told "rows are missing"
 * rather than "fund X is unknown" — the same fault, but only one of those answers leads anywhere.
 * And a future date is reported as future even when it is also a holiday, because "you cannot value
 * a fund on a date that has not happened" is the useful answer; "next Tuesday is a holiday" invites
 * a resend dated next Wednesday, which is also in the future.
 */
@Component
public class ValuationBatchValidator {

  private final FundDirectory funds;
  private final AcceptedValuationRepository accepted;
  private final BusinessCalendar calendar;
  private final Clock clock;

  public ValuationBatchValidator(
      FundDirectory funds,
      AcceptedValuationRepository accepted,
      BusinessCalendar calendar,
      Clock clock) {
    this.funds = funds;
    this.accepted = accepted;
    this.calendar = calendar;
    this.clock = clock;
  }

  /**
   * @return the valuations this batch should record — every row that is genuinely new. Rows that
   *     redeliver an identical accepted valuation are omitted: they are idempotent no-ops, which is
   *     why an accepted batch's valuation count can be lower than its declared row count.
   * @throws ValuationRejectedException any check failed. The whole file is rejected; nothing is
   *     recorded.
   */
  public List<AcceptedValuation> validate(ValuationBatch batch, ValuationFile file) {
    requireDeclaredRowCount(batch, file);
    requireNoDuplicateRows(file);

    Instant now = clock.instant();
    LocalDate today = calendar.businessDateOf(now);
    List<AcceptedValuation> toRecord = new ArrayList<>();
    for (ValuationFile.ValuationRow row : file.rows()) {
      requireNotInFuture(row, today);
      requireBusinessDate(row);
      requireKnownFund(row);
      reconcileAgainstAccepted(row, batch.batchId(), now).ifPresent(toRecord::add);
    }
    return List.copyOf(toRecord);
  }

  /** Check 4 — a cheap truncation guard, before any row is examined. */
  private void requireDeclaredRowCount(ValuationBatch batch, ValuationFile file) {
    int actual = file.rows().size();
    if (actual != batch.declaredRowCount()) {
      throw reject(
          ValuationRejectionCode.ROW_COUNT_MISMATCH,
          "The file contains a different number of valuation rows than the submission declared.",
          Map.of("declaredRowCount", batch.declaredRowCount(), "actualRowCount", actual));
    }
  }

  /**
   * Check 5 — one fund is valued once per date.
   *
   * <p>Two rows naming the same fund and date are refused even when they carry the same amount. A
   * file that says the same thing twice is a producer fault, and picking one of them would be this
   * service guessing which delivery was meant.
   */
  private void requireNoDuplicateRows(ValuationFile file) {
    Set<String> seen = new HashSet<>();
    for (ValuationFile.ValuationRow row : file.rows()) {
      if (!seen.add(row.fundId().value() + "@" + row.valuationDate())) {
        throw reject(
            ValuationRejectionCode.DUPLICATE_ROW,
            "The file values the same fund twice for the same valuation date.",
            Map.of(
                "fundId", row.fundId().value().toString(),
                "valuationDate", row.valuationDate().toString()));
      }
    }
  }

  /** Check 6 — you cannot value a fund on a date that has not happened. */
  private void requireNotInFuture(ValuationFile.ValuationRow row, LocalDate today) {
    if (row.valuationDate().isAfter(today)) {
      throw reject(
          ValuationRejectionCode.FUTURE_VALUATION_DATE,
          "The file carries a valuation dated after the current business date.",
          Map.of(
              "fundId", row.fundId().value().toString(),
              "valuationDate", row.valuationDate().toString(),
              "currentBusinessDate", today.toString()));
    }
  }

  /** Check 7 — a weekend or holiday is not a date the fund is valued on (Task 7.1). */
  private void requireBusinessDate(ValuationFile.ValuationRow row) {
    if (!calendar.isBusinessDate(row.valuationDate())) {
      throw reject(
          ValuationRejectionCode.NON_BUSINESS_DATE,
          "The file carries a valuation dated on a weekend or a market holiday.",
          Map.of(
              "fundId", row.fundId().value().toString(),
              "valuationDate", row.valuationDate().toString()));
    }
  }

  /**
   * Check 8 — the fund is resolved <b>as of the row's own valuation date</b>, never as of today.
   * Funds are effective-dated (Task 20.1), so resolving against today would accept a valuation for
   * a date before the fund existed.
   */
  private void requireKnownFund(ValuationFile.ValuationRow row) {
    if (funds.resolveAsOf(row.fundId(), row.valuationDate()).isEmpty()) {
      throw reject(
          ValuationRejectionCode.UNKNOWN_FUND,
          "The file values a fund that is not registered as of that valuation date.",
          Map.of(
              "fundId", row.fundId().value().toString(),
              "valuationDate", row.valuationDate().toString()));
    }
  }

  /**
   * Check 9 — a valuation for a date already live is never an overwrite.
   *
   * <p>Two branches and no third. An identical {@code reportedValueCents} is an idempotent no-op:
   * providers redeliver files routinely and the existing valuation simply stands. A different one
   * is a <b>correction</b>, refused here so that it has to go through Task 20.5, where it needs a
   * reason, an audit trail and (from Phase 9) four eyes — a correction supersedes a NAV that
   * allotments may already have been struck against, and letting a redelivered file reprice a fund
   * silently would make every one of those controls optional.
   *
   * <p>{@code providerReference} is deliberately not compared. A provider restating the same
   * balance under a new reference is still the same valuation, and treating that as a contradiction
   * would reject correct files.
   *
   * @return the valuation to record, or empty when this row is a no-op
   */
  private Optional<AcceptedValuation> reconcileAgainstAccepted(
      ValuationFile.ValuationRow row, UUID batchId, Instant now) {
    Optional<AcceptedValuation> existing = accepted.find(row.fundId(), row.valuationDate());
    if (existing.isEmpty()) {
      return Optional.of(newValuation(row, batchId, now));
    }
    if (existing.get().reportedValueMinorUnits() == row.reportedValueMinorUnits()) {
      return Optional.empty();
    }
    throw reject(
        ValuationRejectionCode.VALUATION_ALREADY_LIVE,
        "A different valuation is already live for this fund and date. This is a correction, and a"
            + " correction supersedes the NAV struck from the original — it cannot be delivered as"
            + " an ordinary valuation file.",
        Map.of(
            "fundId", row.fundId().value().toString(),
            "valuationDate", row.valuationDate().toString()));
  }

  /**
   * The valuation id is random rather than derived, and that is safe here where it would not be in
   * a ledger path: {@code (fund_id, valuation_date)} is uniquely constrained, so a retry that
   * generated a fresh id still collides on the pair and cannot produce two rows for one valuation.
   * The id is a handle; the pair is the identity.
   */
  private static AcceptedValuation newValuation(
      ValuationFile.ValuationRow row, UUID batchId, Instant now) {
    return new AcceptedValuation(
        UUID.randomUUID(),
        batchId,
        row.fundId(),
        row.valuationDate(),
        row.reportedValueMinorUnits(),
        row.providerReference(),
        now);
  }

  private static ValuationRejectedException reject(
      ValuationRejectionCode code, String message, Map<String, Object> details) {
    return new ValuationRejectedException(ValuationRejection.of(code, message, details));
  }
}
