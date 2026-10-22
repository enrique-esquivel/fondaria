package dev.fondaria.demo.valuation;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a `valuation-file-v1.md` rectangle: one row per fund, each fund's reported value moved by
 * this cycle's scripted NAV move and topped up by contributions that earlier strikes have already
 * allotted. Cash is invested only after it is allotted (row 26h), so today's own contributions are
 * never in today's value -- they would move the struck NAV by more than the market did.
 */
public final class ValuationFileGenerator {

  public static final String COLUMN_HEADER =
      "fundId,valuationDate,reportedValueCents,providerReference";

  private ValuationFileGenerator() {}

  /** One fund's inputs for this valuation date. */
  public record FundValuationInput(
      String fundId, long previousReportedValueCents, long allottedContributionsCents) {}

  public record GeneratedFile(
      String csv, int rowCount, Map<String, Long> reportedValueCentsByFund) {}

  /**
   * A fund's very first valuation: the reported value is written as-is, with no previous value to
   * move from. What it says barely matters -- Model B falls back to the fund's opening price
   * whenever units in issue are still zero (`NavStrikeService`), which every fund's very first
   * strike always is.
   */
  public static GeneratedFile generateInitial(
      LocalDate valuationDate,
      Map<String, Long> reportedValueCentsByFund,
      String providerReference) {
    StringBuilder csv = new StringBuilder(COLUMN_HEADER).append('\n');
    for (Map.Entry<String, Long> entry : reportedValueCentsByFund.entrySet()) {
      csv.append(entry.getKey())
          .append(',')
          .append(valuationDate)
          .append(',')
          .append(entry.getValue())
          .append(',')
          .append(providerReference)
          .append('\n');
    }
    return new GeneratedFile(
        csv.toString(), reportedValueCentsByFund.size(), reportedValueCentsByFund);
  }

  public static GeneratedFile generate(
      LocalDate valuationDate,
      long moveScaled,
      List<FundValuationInput> funds,
      String providerReference) {
    StringBuilder csv = new StringBuilder(COLUMN_HEADER).append('\n');
    Map<String, Long> reportedByFund = new LinkedHashMap<>();
    for (FundValuationInput fund : funds) {
      long reportedValueCents =
          movedValue(fund.previousReportedValueCents(), moveScaled)
              + fund.allottedContributionsCents();
      csv.append(fund.fundId())
          .append(',')
          .append(valuationDate)
          .append(',')
          .append(reportedValueCents)
          .append(',')
          .append(providerReference)
          .append('\n');
      reportedByFund.put(fund.fundId(), reportedValueCents);
    }
    return new GeneratedFile(csv.toString(), funds.size(), reportedByFund);
  }

  /**
   * {@code previous × (1 + moveScaled ÷ MOVE_SCALE)}, half-up, exact {@link BigInteger} arithmetic
   * -- the same convention {@code Money.value} uses, never a {@code double} (AGENTS.md §4).
   */
  private static long movedValue(long previousReportedValueCents, long moveScaled) {
    BigInteger previous = BigInteger.valueOf(previousReportedValueCents);
    BigInteger scale = BigInteger.valueOf(NavSeries.MOVE_SCALE);
    BigInteger delta = roundedDivide(previous.multiply(BigInteger.valueOf(moveScaled)), scale);
    return previous.add(delta).longValueExact();
  }

  private static BigInteger roundedDivide(BigInteger numerator, BigInteger divisor) {
    BigInteger[] divRem = numerator.divideAndRemainder(divisor);
    BigInteger quotient = divRem[0];
    BigInteger remainder = divRem[1].abs();
    if (remainder.multiply(BigInteger.TWO).compareTo(divisor.abs()) >= 0) {
      quotient = quotient.add(BigInteger.valueOf(numerator.signum() * divisor.signum()));
    }
    return quotient;
  }
}
