package dev.fondaria.rules;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One effective-dated version of a fund — {@code reference.fund} joined with its immutable identity
 * row, {@code reference.fund_identity} (Task 20.1).
 *
 * <p>Two groups of fields, deliberately not the same lifecycle. {@code unitLedgerId}, {@code
 * baseCurrency}, {@code unitScale}, {@code inceptionDate} and {@code openingNav} are assigned once,
 * at the fund's first registration, and never change — a fund is never split across TigerBeetle
 * clusters and its unit ledger id is never reused (AGENTS.md §4, {@code LedgerPort} resolves a
 * cluster by fund). {@code name}, {@code vehicleType} and {@code status} are effective-dated and
 * append-only, the pattern {@link PlanVersion} set: a correction is a new version, never an edit.
 * {@link FundRepository#publish} carries both every time, because a caller resolving a fund as of a
 * date needs the whole picture in one read, but only the second group ever varies between two
 * versions of the same fund.
 *
 * @param effectiveFrom the date this version takes effect. Resolution ({@link
 *     FundRepository#resolveAsOf}) picks the version with the latest {@code effectiveFrom} not
 *     after the date asked for.
 * @param version disambiguates two versions published with the same {@code effectiveFrom} — a
 *     correction of a mistaken publication. The higher value wins resolution; the lower one remains
 *     readable.
 * @param unitLedgerId the TigerBeetle ledger id carrying this fund's units, assigned once at
 *     registration and never reused by another fund.
 * @param unitScale the fixed-point scale units are stored at. Always {@link Units#SCALE} — carried
 *     as data, not a constant, so a stored row reproduces its own arithmetic without rerunning
 *     current code (AGENTS.md §4).
 * @param openingNav the price the fund's first contribution buys at, declared at inception.
 */
public record Fund(
    FundId fundId,
    LocalDate effectiveFrom,
    int version,
    String name,
    String vehicleType,
    CurrencyCode baseCurrency,
    long unitLedgerId,
    long unitScale,
    LocalDate inceptionDate,
    Nav openingNav,
    FundStatus status,
    Instant publishedAt) {

  public Fund {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
    Objects.requireNonNull(baseCurrency, "baseCurrency must not be null");
    Objects.requireNonNull(inceptionDate, "inceptionDate must not be null");
    Objects.requireNonNull(openingNav, "openingNav must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    name = name.trim();
    if (vehicleType == null || vehicleType.isBlank()) {
      throw new IllegalArgumentException("vehicleType must not be blank");
    }
    vehicleType = vehicleType.trim();
    if (version < 1) {
      throw new IllegalArgumentException("version must be at least 1");
    }
    if (unitLedgerId <= 0) {
      throw new IllegalArgumentException("unitLedgerId must be positive");
    }
    if (unitScale != Units.SCALE) {
      throw new IllegalArgumentException("unitScale must be " + Units.SCALE);
    }
    if (openingNav.scaled() <= 0) {
      throw new IllegalArgumentException("openingNav must be positive");
    }
    if (openingNav.currency() != baseCurrency) {
      throw new IllegalArgumentException("openingNav currency must match baseCurrency");
    }
  }
}
