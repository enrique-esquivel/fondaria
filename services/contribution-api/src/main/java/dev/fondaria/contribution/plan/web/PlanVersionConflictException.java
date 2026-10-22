package dev.fondaria.contribution.plan.web;

import dev.fondaria.common.OrganizationId;
import java.io.Serial;

/**
 * A {@code POST .../plans} replay named an {@code (organizationId, planId, version)} already
 * published, but with different content — the one case a replay is not a no-op (409).
 */
public final class PlanVersionConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final OrganizationId organizationId;
  private final String planId;
  private final int version;

  public PlanVersionConflictException(OrganizationId organizationId, String planId, int version) {
    super(
        "Plan version "
            + planId
            + " version "
            + version
            + " for organization "
            + organizationId
            + " already exists with different content");
    this.organizationId = organizationId;
    this.planId = planId;
    this.version = version;
  }

  public OrganizationId organizationId() {
    return organizationId;
  }

  public String planId() {
    return planId;
  }

  public int version() {
    return version;
  }
}
