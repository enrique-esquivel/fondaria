package dev.fondaria.demo;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formatting for what this module prints, and nothing else. {@code BigDecimal} appears here and
 * only here: the display boundary is the one place `docs/ENGINEERING-RULES.md` §4 allows it, and
 * the value arrives and is checked as {@code long} cents before it is ever formatted.
 */
public final class Display {

  private Display() {}

  /** {@code 123456} -> {@code $1,234.56}; {@code -5} -> {@code -$0.05}. Signed, never clamped. */
  public static String pesos(long cents) {
    DecimalFormat format =
        new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    BigDecimal value = BigDecimal.valueOf(cents, 2);
    String digits = format.format(value.abs());
    return (value.signum() < 0 ? "-$" : "$") + digits;
  }

  /** {@code 1234567} -> {@code 1,234,567}. */
  public static String count(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  /** The first twelve hex digits of a hash -- enough to recognise, short enough to read aloud. */
  public static String shortHash(String sha256) {
    return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12) + "…";
  }
}
