package dev.fondaria.statementworker.contribution;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import java.util.Objects;

/**
 * One participant's contributions for one statement period, split by source (Task 7.5b) — kept
 * separate all the way through because {@code docs/DOMAIN.md} §4.2 requires it: employee and
 * employer contributions are shown separately on the waterfall since their tax treatment and
 * vesting differ ({@code ADR-0004}).
 */
public record ContributionTotals(Money employee, Money employer, Money voluntary) {

  public ContributionTotals {
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(employer, "employer must not be null");
    Objects.requireNonNull(voluntary, "voluntary must not be null");
  }

  public static ContributionTotals zero(CurrencyCode currency) {
    return new ContributionTotals(
        Money.of(0, currency), Money.of(0, currency), Money.of(0, currency));
  }

  public ContributionTotals plus(String source, Money amount) {
    return switch (source) {
      case "EMPLOYEE" -> new ContributionTotals(employee.plus(amount), employer, voluntary);
      case "EMPLOYER" -> new ContributionTotals(employee, employer.plus(amount), voluntary);
      case "VOLUNTARY" -> new ContributionTotals(employee, employer, voluntary.plus(amount));
      default -> throw new IllegalArgumentException("unknown allotment source: " + source);
    };
  }
}
