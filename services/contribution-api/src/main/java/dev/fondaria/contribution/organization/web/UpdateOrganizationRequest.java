package dev.fondaria.contribution.organization.web;

import dev.fondaria.contribution.organization.OrganizationChange;
import dev.fondaria.contribution.organization.OrganizationStatus;
import java.util.Optional;

/**
 * Update request body. An absent field means "leave as it is"; there is no way to express "change
 * the tax identifier", because that would change the derived id and orphan the ledger accounts
 * already holding this employer's money.
 *
 * <p>Blank is treated as absent rather than as "clear this field", so a client sending {@code ""}
 * for a field it does not manage cannot wipe one it never saw.
 */
public record UpdateOrganizationRequest(
    String legalName, String contractReference, String feeBracket, String status) {

  public OrganizationChange toChange() {
    return new OrganizationChange(
        Requests.optionalText(legalName),
        Requests.optionalText(contractReference),
        Requests.optionalText(feeBracket),
        parseStatus());
  }

  private Optional<OrganizationStatus> parseStatus() {
    Optional<String> raw = Requests.optionalText(status);
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(OrganizationStatus.parse(raw.get()));
    } catch (IllegalArgumentException e) {
      throw new InvalidOrganizationRequestException(e.getMessage());
    }
  }
}
