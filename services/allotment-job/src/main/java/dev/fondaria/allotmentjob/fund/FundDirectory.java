package dev.fondaria.allotmentjob.fund;

import dev.fondaria.common.FundId;
import dev.fondaria.rules.Fund;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Read-only access to the fund registry {@code reference.fund} / {@code reference.fund_identity}
 * (Task 20.1).
 *
 * <p>Narrower than {@code rules-engine}'s {@code FundRepository} on purpose, the same reason
 * valuation-api's {@code FundDirectory} is: that port carries {@code publish}, and this service
 * must never publish a fund. Narrower still than that class — this service has no ageing report, so
 * it needs only {@link #resolveAsOf}, not an "every active fund" query.
 */
public interface FundDirectory {

  /**
   * The fund version live on {@code asOfDate} — the batch's dealing date, so a fund resolved
   * against a date it did not yet exist on is correctly absent rather than silently wrong.
   */
  Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate);
}
