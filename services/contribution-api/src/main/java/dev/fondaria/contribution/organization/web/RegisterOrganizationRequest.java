package dev.fondaria.contribution.organization.web;

import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import java.util.Locale;

/**
 * Registration request body. This is the only place in the whole API where a tax identifier
 * appears, and it appears in a body rather than a path on purpose: a path reaches an access log, a
 * proxy log and a trace (AGENTS.md §7).
 *
 * @param taxIdScheme which national scheme {@code taxId} belongs to; {@code MX_RFC} in v1
 * @param taxId the employer's tax identifier, in any spelling — it is canonicalized here, so {@code
 *     "abc-010101-xy1"} and {@code "ABC010101XY1"} register one organization, not two
 */
public record RegisterOrganizationRequest(
    String taxIdScheme,
    String taxId,
    String legalName,
    String contractReference,
    String feeBracket) {

  public RegisterOrganizationCommand toCommand() {
    TaxIdScheme scheme = parseScheme(taxIdScheme);
    TaxIdentifier taxIdentifier;
    try {
      taxIdentifier = TaxIdentifier.of(scheme, taxId);
    } catch (IllegalArgumentException e) {
      // TaxIdScheme's messages are written never to include the offending value.
      throw new InvalidOrganizationRequestException("taxId is not valid: " + e.getMessage());
    }
    if (legalName == null || legalName.isBlank()) {
      throw new InvalidOrganizationRequestException("legalName must not be blank");
    }
    return new RegisterOrganizationCommand(
        taxIdentifier,
        legalName,
        Requests.optionalText(contractReference),
        Requests.optionalText(feeBracket));
  }

  private static TaxIdScheme parseScheme(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidOrganizationRequestException(
          "taxIdScheme must be supplied; supported: " + Requests.supportedSchemes());
    }
    try {
      return TaxIdScheme.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new InvalidOrganizationRequestException(
          "taxIdScheme is not supported; supported: " + Requests.supportedSchemes());
    }
  }

  /** Never rendered anywhere; present only so a debugger cannot print {@link #taxId}. */
  @Override
  public String toString() {
    return "RegisterOrganizationRequest[taxIdScheme="
        + taxIdScheme
        + ", taxId=REDACTED, legalName=…]";
  }
}
