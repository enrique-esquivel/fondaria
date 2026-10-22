package dev.fondaria.rules;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Storage for {@code reference.fund} and its companion identity row, {@code
 * reference.fund_identity} (Task 20.1).
 *
 * <p>An interface for the same reason {@link PlanVersionRepository} is one: a caller that only
 * needs to resolve a fund as of a date should not need a database in the loop to be tested. The one
 * JDBC implementation (in the owning service) proves the SQL — including that two funds cannot
 * claim the same unit ledger id, and that {@code reference}'s append-only grant holds — separately,
 * against a real PostgreSQL.
 */
public interface FundRepository {

  /**
   * The version in force for {@code fundId} on {@code asOfDate}: the highest {@code (effectiveFrom,
   * version)} not after that date.
   */
  Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate);

  /**
   * Appends a new fund version. The first call for a given {@code fundId} also registers its
   * identity — {@code unitLedgerId}, {@code baseCurrency}, {@code unitScale}, {@code inceptionDate}
   * and {@code openingNav} — which is fixed from then on; every later call for the same {@code
   * fundId} must carry the identical identity fields, since only the version row is appended
   * afterward.
   *
   * <p>Never updates or deletes an existing version — a correction is a new row, alongside the one
   * it corrects, which remains readable.
   */
  Fund publish(Fund candidate);
}
