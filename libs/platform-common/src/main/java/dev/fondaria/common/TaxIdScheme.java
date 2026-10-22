package dev.fondaria.common;

import java.util.Locale;

/**
 * The national tax-identifier scheme an organization is registered under, together with the rules
 * for a well-formed value in it.
 *
 * <p>v1 is Mexico-only (ADR-0004), so there is exactly one constant. The scheme is still modelled
 * explicitly rather than assumed, because it is part of the input from which an organization's id
 * and its ledger account ids are derived: without it, a Mexican {@code RFC} and some future
 * country's identifier that happened to share the same string would derive the <b>same</b> account.
 * Adding a country is one self-contained constant here.
 *
 * <p>Values are canonicalized before validation, so the same registration keyed as {@code
 * "abc-010101-xy1"} and {@code "ABC010101XY1"} is one organization rather than two.
 *
 * <h2>What this is deliberately not</h2>
 *
 * <p><b>Not a general identity-document type.</b> A {@code CURP} is a population-registry
 * identifier, not a tax identifier, and does not belong here however tempting the shape match is:
 * it identifies a person rather than a taxpayer, it is issued by a different authority, and it
 * validates by different rules. Participant identity (Phase 16) needs its own type. Widening this
 * enum to hold both would produce a value whose meaning depends on which constant it carries, which
 * is exactly the ambiguity the type exists to remove.
 *
 * <p><b>Not open at runtime.</b> Adding a country means adding a constant and deploying, because a
 * scheme is validation *logic*, not configuration — the {@code RFC}'s embedded {@code YYMMDD} date
 * cannot be expressed as a reference-data row without inventing a rule language to express it in.
 * That is a deliberate trade while v1 is Mexico-only (`ADR-0004`); if several countries ever land
 * at once, revisit it then rather than pre-building for one.
 */
public enum TaxIdScheme {

  /**
   * Mexico — Registro Federal de Contribuyentes. Twelve characters for a <i>persona moral</i> (a
   * company, which is what an employer normally is) and thirteen for a <i>persona física</i> (a
   * sole trader who employs people). Both shapes are accepted: an employer may legitimately be
   * either.
   *
   * <p><b>The check digit is deliberately not enforced.</b> The final character of the homoclave is
   * a {@code dígito verificador} and validating it would catch more keying errors than the
   * structural rules below. It is not implemented here because a wrong implementation rejects
   * legitimate employers — which blocks real money — and the algorithm has documented exceptions
   * (the generic RFCs {@code XAXX010101000} and {@code XEXX010101000} do not satisfy it). Adding it
   * requires verification against SAT reference data first.
   */
  MX_RFC("MX", "RFC") {
    @Override
    public String canonicalize(String raw) {
      String value = strip(raw).toUpperCase(Locale.ROOT);
      if (value.length() != 12 && value.length() != 13) {
        throw new IllegalArgumentException(
            "RFC must be 12 characters (persona moral) or 13 (persona física), got: "
                + value.length());
      }
      int letters = value.length() == 12 ? 3 : 4;
      for (int i = 0; i < letters; i++) {
        if (!isNameCharacter(value.charAt(i))) {
          throw new IllegalArgumentException(
              "RFC must begin with " + letters + " letters at position " + (i + 1));
        }
      }
      requireDate(value.substring(letters, letters + 6));
      for (int i = letters + 6; i < value.length(); i++) {
        char c = value.charAt(i);
        if (!isAsciiDigit(c) && !isNameCharacter(c)) {
          throw new IllegalArgumentException(
              "RFC homoclave must be alphanumeric at position " + (i + 1));
        }
      }
      return value;
    }
  };

  private final String country;
  private final String code;

  TaxIdScheme(String country, String code) {
    this.country = country;
    this.code = code;
  }

  /** ISO 3166-1 alpha-2 country code. */
  public String country() {
    return country;
  }

  /** The scheme's own name as the issuing authority writes it, e.g. {@code RFC} (AGENTS.md §4a). */
  public String code() {
    return code;
  }

  /**
   * Canonicalizes {@code raw}, throwing {@link IllegalArgumentException} if it is not a well-formed
   * value in this scheme. Never include the offending value in the message: a tax identifier is on
   * the never-log list (AGENTS.md §7), and an exception message reaches a log.
   */
  public abstract String canonicalize(String raw);

  /**
   * The scheme half of the string an organization id and its account ids are derived from. Kept
   * distinct from {@link #name()} so that renaming a constant cannot silently re-derive every
   * account under a different id.
   */
  public String derivationPrefix() {
    return country + ":" + code;
  }

  private static String strip(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("tax identifier must not be null");
    }
    StringBuilder stripped = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c != ' ' && c != '-' && c != '.') {
        stripped.append(c);
      }
    }
    return stripped.toString();
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** {@code Ñ} and {@code &} are both legal in an RFC's name portion. */
  private static boolean isNameCharacter(char c) {
    return (c >= 'A' && c <= 'Z') || c == 'Ñ' || c == '&';
  }

  /**
   * The six date digits are {@code YYMMDD} with no century, so the year is unconstrained and
   * February is allowed 29 days rather than resolved against a leap year that cannot be known.
   * Rejecting a legitimate identifier is the more expensive error here.
   */
  private static void requireDate(String yymmdd) {
    for (int i = 0; i < yymmdd.length(); i++) {
      if (!isAsciiDigit(yymmdd.charAt(i))) {
        throw new IllegalArgumentException("RFC date portion must be 6 digits (YYMMDD)");
      }
    }
    int month = Integer.parseInt(yymmdd.substring(2, 4));
    int day = Integer.parseInt(yymmdd.substring(4, 6));
    if (month < 1 || month > 12) {
      throw new IllegalArgumentException("RFC date portion has a month outside 01-12");
    }
    int maxDay =
        switch (month) {
          case 2 -> 29;
          case 4, 6, 9, 11 -> 30;
          default -> 31;
        };
    if (day < 1 || day > maxDay) {
      throw new IllegalArgumentException("RFC date portion has a day outside the month's range");
    }
  }
}
