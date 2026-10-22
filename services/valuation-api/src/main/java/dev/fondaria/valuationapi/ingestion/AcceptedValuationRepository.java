package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Storage for {@code workflow.valuation} — the valuations that have been accepted.
 *
 * <p>An interface for the reason the contribution repositories are: what the validator concludes,
 * and what the ageing report reports, should be provable without a database in the loop. The one
 * JDBC implementation proves the SQL separately against a real PostgreSQL.
 */
public interface AcceptedValuationRepository {

  /** The accepted valuation for this fund and date, if one exists. */
  Optional<AcceptedValuation> find(FundId fundId, LocalDate valuationDate);

  /**
   * Records valuations that are new. Never updates one, and never deletes one: the only two things
   * that may happen to a live valuation are an identical redelivery (which does not reach here) and
   * a correction (Task 20.5, which supersedes rather than overwrites).
   *
   * @return how many rows were inserted
   */
  int insertAll(Collection<AcceptedValuation> valuations);

  /**
   * The valuation dates already accepted for {@code fundId} within an inclusive date range — the
   * ageing report's positive set, from which it derives the absences.
   *
   * <p>Returns the dates rather than the valuations: the report only asks which business dates are
   * covered, and pulling whole rows to answer a membership question would read every valuation in
   * the window for every fund.
   */
  List<LocalDate> valuationDatesBetween(
      FundId fundId, LocalDate fromInclusive, LocalDate toInclusive);
}
