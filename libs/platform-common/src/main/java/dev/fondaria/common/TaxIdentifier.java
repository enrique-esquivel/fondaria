package dev.fondaria.common;

import java.util.Objects;

/**
 * An organization's national tax identifier — in Mexico its {@code RFC} — canonicalized and checked
 * for shape at construction, so an ill-formed value cannot exist as an instance of this type.
 *
 * <p>This is the identity an organization is known by. It is deliberately <b>not</b> what appears
 * in URLs, logs or messages: a tax identifier is on the never-log list (docs/ENGINEERING-RULES.md
 * §7). Callers derive an opaque id from it via {@code derivationKey()} and pass that around
 * instead, which is why this record has no {@code toString()} carrying the value.
 *
 * @param scheme which country's scheme {@code value} belongs to
 * @param value the canonical (stripped, upper-case) identifier
 */
public record TaxIdentifier(TaxIdScheme scheme, String value) {

  public TaxIdentifier {
    Objects.requireNonNull(scheme, "scheme must not be null");
    value = scheme.canonicalize(value);
  }

  public static TaxIdentifier of(TaxIdScheme scheme, String rawValue) {
    return new TaxIdentifier(scheme, rawValue);
  }

  /**
   * The stable string an organization id and its ledger account ids are derived from. Includes the
   * scheme, so two countries' identifiers can never collide onto one organization even if their
   * values are textually identical.
   */
  public String derivationKey() {
    return scheme.derivationPrefix() + ":" + value;
  }

  /**
   * Redacted on purpose. A tax identifier reaches a log the moment it is interpolated into a
   * message, an exception or a debugger-friendly {@code toString()}; the scheme is safe to show and
   * is all a diagnostic needs.
   */
  @Override
  public String toString() {
    return "TaxIdentifier[" + scheme.derivationPrefix() + ":REDACTED]";
  }
}
