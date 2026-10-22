package dev.fondaria.valuationapi.correction;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.NavCache;
import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Records a correction to a NAV already struck (Task 20.5) — a wrong price is never deleted or
 * edited, only superseded by a new row alongside it (docs/ENGINEERING-RULES.md §4).
 *
 * <p><b>Internal-only.</b> {@code phase-09-identity.md} Task 9.4 names "NAV supersession"
 * explicitly as a four-eyes action, enforced at the database level once Phase 9 exists. Until then
 * this class is not wired to any externally reachable endpoint — its caller is internal tooling
 * inside the cluster, not a public API.
 *
 * <p><b>Takes the corrected price directly, not a re-derivation.</b> {@code unitsInIssue} and
 * {@code source} are carried forward unchanged from the row being superseded: a correction fixes
 * the number that was wrong (the struck price), not the units-in-issue reading the original strike
 * already recorded, which stays exactly reproducible from the corrected row alone
 * (docs/ENGINEERING-RULES.md §4).
 *
 * <p><b>Never touches {@code workflow.allotment}.</b> Every allotment already struck under the
 * superseded price keeps naming it — re-allotment against a corrected NAV is a Phase 14 concern,
 * never a side effect of publishing a correction (Task 20.5's own requirements).
 */
@Service
public class NavCorrectionService {

  private final FundNavRepository fundNavRepository;
  private final NavCache navCache;
  private final Clock clock;

  public NavCorrectionService(FundNavRepository fundNavRepository, NavCache navCache, Clock clock) {
    this.fundNavRepository =
        Objects.requireNonNull(fundNavRepository, "fundNavRepository must not be null");
    this.navCache = Objects.requireNonNull(navCache, "navCache must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * @throws NoSuchElementException no live strike exists for {@code fundId} on {@code
   *     valuationDate} — there is nothing to correct
   * @throws IllegalArgumentException {@code reason} is null or blank
   */
  public FundNav correct(FundId fundId, LocalDate valuationDate, Nav correctedNav, String reason) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    Objects.requireNonNull(correctedNav, "correctedNav must not be null");

    FundNav superseded =
        fundNavRepository
            .findLive(fundId, valuationDate)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "No live NAV to correct for " + fundId + " on " + valuationDate));

    FundNav candidate =
        new FundNav(
            0L,
            fundId,
            valuationDate,
            correctedNav,
            superseded.unitsInIssue(),
            superseded.source(),
            superseded.navVersionId(),
            reason,
            clock.instant());
    FundNav corrected = fundNavRepository.correct(candidate);

    // The sharper of the two events NavCache#refresh's own javadoc names: between this write
    // and an instance's next sweep, it serves a price somebody has already declared wrong.
    navCache.refresh(fundId);
    return corrected;
  }
}
