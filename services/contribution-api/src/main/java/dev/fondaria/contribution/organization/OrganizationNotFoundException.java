package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import java.io.Serial;

/**
 * No organization is registered under this id. Carries the id, which is safe to render: it is the
 * opaque derived value, never the tax identifier it came from.
 */
public final class OrganizationNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final OrganizationId organizationId;

  public OrganizationNotFoundException(OrganizationId organizationId) {
    super("No organization is registered under id " + organizationId);
    this.organizationId = organizationId;
  }

  public OrganizationId organizationId() {
    return organizationId;
  }
}
