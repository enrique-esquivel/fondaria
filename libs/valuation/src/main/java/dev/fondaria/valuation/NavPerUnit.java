package dev.fondaria.valuation;

import dev.fondaria.common.CurrencyMismatchException;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.math.BigInteger;
import java.util.Objects;

/**
 * A struck price per unit, together with the units in issue it was struck against.
 *
 * <p>Carrying {@code unitsInIssue} alongside the result (not just the price) is what lets Task 20.4
 * persist a division that reproduces itself years later from stored data alone, never by rerunning
 * current code (docs/ENGINEERING-RULES.md §4).
 */
public record NavPerUnit(Nav nav, Units unitsInIssue) {

  /**
   * The combined fixed-point scale of {@link Units} (1e8) and {@link Nav} (1e8) is 1e16; {@link
   * Money} is cents (scale 1e2). To turn {@code reportedValue.cents() ÷ unitsInIssue.scaled()} into
   * a Nav-scaled result we multiply the numerator by 10^(8+8-2) = 10^14 before dividing — the same
   * divisor {@code Money.value(Units, Nav)} uses for the reverse multiplication.
   */
  private static final BigInteger SCALE_ADJUSTMENT = BigInteger.valueOf(100_000_000_000_000L);

  public NavPerUnit {
    Objects.requireNonNull(nav, "nav must not be null");
    Objects.requireNonNull(unitsInIssue, "unitsInIssue must not be null");
  }

  /**
   * Model B ({@code docs/DOMAIN.md} §3): {@code nav = reportedValue ÷ unitsInIssue}.
   *
   * <p>{@code unitsInIssue == 0} — a fund before its first allotment — is not a division by zero:
   * the fund's declared {@code openingNav} is returned unchanged, the price its first contribution
   * buys at (Task 20.1).
   *
   * <p>Integer arithmetic only (docs/ENGINEERING-RULES.md §4): the division is carried out exactly
   * in {@link BigInteger}, and its remainder is computed explicitly and used to round half-up — the
   * same convention {@code Money.value(Units, Nav)} uses — rather than a bare {@code /} that would
   * truncate the remainder unnoticed. A falling {@code reportedValue} produces a proportionally
   * lower {@code nav}; nothing here floors or clamps it.
   */
  public static NavPerUnit strike(Money reportedValue, Units unitsInIssue, Nav openingNav) {
    Objects.requireNonNull(reportedValue, "reportedValue must not be null");
    Objects.requireNonNull(unitsInIssue, "unitsInIssue must not be null");
    Objects.requireNonNull(openingNav, "openingNav must not be null");
    if (reportedValue.currency() != openingNav.currency()) {
      throw new CurrencyMismatchException(openingNav.currency(), reportedValue.currency());
    }
    if (reportedValue.isNegative()) {
      throw new IllegalArgumentException(
          "reportedValue cannot be negative, got: " + reportedValue.cents());
    }

    if (unitsInIssue.isZero()) {
      return new NavPerUnit(openingNav, unitsInIssue);
    }

    BigInteger numerator = BigInteger.valueOf(reportedValue.cents()).multiply(SCALE_ADJUSTMENT);
    BigInteger divisor = BigInteger.valueOf(unitsInIssue.scaled());

    BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(divisor);
    BigInteger quotient = quotientAndRemainder[0];
    BigInteger remainder = quotientAndRemainder[1];

    if (remainder.multiply(BigInteger.TWO).compareTo(divisor) >= 0) {
      quotient = quotient.add(BigInteger.ONE);
    }

    return new NavPerUnit(
        Nav.of(quotient.longValueExact(), reportedValue.currency()), unitsInIssue);
  }
}
