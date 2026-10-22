package dev.fondaria.contribution.fund.web;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The body a caller submits to register a fund or publish a corrected version of one (Task D.0,
 * {@code phase-D-demo.md}).
 *
 * <p>Mirrors {@code dev.fondaria.rules.Fund}, minus {@code unitLedgerId}: the platform assigns the
 * next free one, the caller never chooses it. Every accessor here does its own parsing rather than
 * leaning on {@code Fund}'s constructor for everything, because the two things that constructor
 * cannot catch — a malformed date string and a currency this platform does not support — need their
 * own message before a value ever reaches it.
 *
 * @param fundId omit to register a new fund; supply the id an earlier call returned (or one this
 *     caller generated itself) to publish a corrected version, or to retry a call whose response
 *     was lost
 * @param unitScale must equal {@link dev.fondaria.common.Units#SCALE} — carried as a request field
 *     rather than assumed, per {@code Fund}'s own javadoc, even though v1 has only one valid value
 * @param launchDate the fund's inception date
 * @param launchNavScaled the opening price, 1e-8 scaled, a new fund's first contribution buys at
 */
public record RegisterFundRequest(
    String fundId,
    String effectiveFrom,
    Integer version,
    String name,
    String vehicleType,
    String currency,
    Long unitScale,
    String launchDate,
    Long launchNavScaled) {

  Optional<FundId> parsedFundId() {
    if (fundId == null || fundId.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(FundId.parse(fundId.trim()));
    } catch (IllegalArgumentException e) {
      throw new InvalidFundRequestException("fundId is not a valid UUID");
    }
  }

  LocalDate parsedEffectiveFrom() {
    return parseDate(effectiveFrom, "effectiveFrom");
  }

  int parsedVersion() {
    if (version == null) {
      throw new InvalidFundRequestException("version must be supplied");
    }
    return version;
  }

  String parsedName() {
    if (name == null || name.isBlank()) {
      throw new InvalidFundRequestException("name must not be blank");
    }
    return name.trim();
  }

  String parsedVehicleType() {
    if (vehicleType == null || vehicleType.isBlank()) {
      throw new InvalidFundRequestException("vehicleType must not be blank");
    }
    return vehicleType.trim();
  }

  /** v1 is MXN-only (ADR-0004), the same restriction {@code RecordCashReceiptRequest} applies. */
  CurrencyCode parsedCurrency() {
    if (currency == null || currency.isBlank()) {
      throw new InvalidFundRequestException("currency must be supplied, and must be MXN");
    }
    if (!CurrencyCode.MXN.name().equalsIgnoreCase(currency.trim())) {
      throw new InvalidFundRequestException("currency must be MXN");
    }
    return CurrencyCode.MXN;
  }

  long parsedUnitScale() {
    if (unitScale == null) {
      throw new InvalidFundRequestException("unitScale must be supplied");
    }
    return unitScale;
  }

  LocalDate parsedLaunchDate() {
    return parseDate(launchDate, "launchDate");
  }

  long parsedLaunchNavScaled() {
    if (launchNavScaled == null) {
      throw new InvalidFundRequestException("launchNavScaled must be supplied");
    }
    return launchNavScaled;
  }

  private static LocalDate parseDate(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidFundRequestException(field + " must be supplied, as YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new InvalidFundRequestException(field + " must be a date in YYYY-MM-DD form");
    }
  }

  /** Never rendered; present only so a debugger's default toString does not print the body. */
  @Override
  public String toString() {
    return "RegisterFundRequest[fundId="
        + fundId
        + ", effectiveFrom="
        + effectiveFrom
        + ", version="
        + version
        + "]";
  }
}
