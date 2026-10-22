package dev.fondaria.rules;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of {@code reference.fund_nav} — a struck price per unit, append-only and versioned (Task
 * 20.4).
 *
 * <p>{@code unitsInIssue} travels with the price rather than being looked up again later: a stored
 * row reproduces its own arithmetic years afterward from data alone, never by rerunning current
 * code (docs/ENGINEERING-RULES.md §4). {@code supersedesId} is null for an original strike and set
 * only by a correction (Task 20.5), which also requires {@code reason}.
 *
 * @param navVersionId the surrogate key. {@code 0} for a candidate not yet persisted.
 */
public record FundNav(
    long navVersionId,
    FundId fundId,
    LocalDate valuationDate,
    Nav nav,
    Units unitsInIssue,
    String source,
    Long supersedesId,
    String reason,
    Instant publishedAt) {

  public FundNav {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(unitsInIssue, "unitsInIssue must not be null");
    Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    if (nav.scaled() <= 0) {
      throw new IllegalArgumentException("nav must be positive");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
    source = source.trim();
    if (supersedesId != null && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("reason is required when supersedesId is set");
    }
    if (reason != null) {
      reason = reason.trim();
    }
  }
}
