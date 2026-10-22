package dev.fondaria.demo.valuation;

import dev.fondaria.demo.DemoToolsException;

/**
 * Parses a human-typed move like {@code "-1.5%"} into {@link NavSeries#MOVE_SCALE}-scaled integer
 * cents, entirely in string/integer arithmetic -- never a {@code double} or {@code BigDecimal} for
 * a market-movement field (`docs/ENGINEERING-RULES.md` §4), even at the command line.
 */
public final class MovePercent {

  private MovePercent() {}

  /**
   * @param input a signed decimal percentage, optionally suffixed with {@code %} (e.g. {@code
   *     "-1.5%"}, {@code "0.3"})
   */
  public static long parse(String input) {
    String trimmed = input.trim();
    String withoutPercent =
        trimmed.endsWith("%") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    boolean negative = withoutPercent.startsWith("-");
    String unsigned =
        negative || withoutPercent.startsWith("+") ? withoutPercent.substring(1) : withoutPercent;

    if (!unsigned.matches("\\d+(\\.\\d+)?")) {
      throw new DemoToolsException("not a valid percentage: " + input);
    }
    String[] parts = unsigned.split("\\.", 2);
    String integerPart = parts[0].isEmpty() ? "0" : parts[0];
    String fractionalPart = parts.length > 1 ? parts[1] : "";
    // NavSeries.MOVE_SCALE (100,000) applied to a percentage (÷100) is a ×1,000 multiplier, exact
    // to 3 fractional digits -- padded if shorter, truncated (not rounded) if a caller supplies
    // more.
    StringBuilder padded = new StringBuilder(fractionalPart);
    while (padded.length() < 3) {
      padded.append('0');
    }
    String threeDigits = padded.substring(0, 3);

    long combined;
    try {
      combined = Long.parseLong(integerPart) * 1000L + Long.parseLong(threeDigits);
    } catch (NumberFormatException e) {
      throw new DemoToolsException("not a valid percentage: " + input, e);
    }
    return negative ? -combined : combined;
  }
}
