package dev.fondaria.common;

import java.util.Objects;
import java.util.UUID;

/**
 * The opaque, stable identifier of a client organization, derived from its tax identifier:
 *
 * <pre>organization_id = UUIDv5(organizationNamespace, taxId.derivationKey())</pre>
 *
 * <p><b>Derived rather than assigned, so no registry is needed.</b> Registering the same employer
 * twice yields the same id, which makes provisioning idempotent without a table to consult, keep in
 * sync, or restore. The identity of an organization is therefore a pure function of who it legally
 * is, not of the order in which rows happened to be inserted.
 *
 * <p><b>This, not the tax identifier, is what travels.</b> An {@code RFC} is on the never-log list
 * (docs/ENGINEERING-RULES.md §7), and anything in a URL path reaches an access log, a proxy log and
 * a trace. So the tax identifier is supplied once, in a request body, and everything downstream —
 * paths, log fields, message keys, ledger account scopes — carries this UUID instead.
 *
 * <p>The namespace comes from configuration and must never change: it is what ties this id to the
 * ledger accounts already holding that organization's money.
 */
public record OrganizationId(UUID value) {

  public OrganizationId {
    Objects.requireNonNull(value, "value must not be null");
  }

  public static OrganizationId derive(UUID organizationNamespace, TaxIdentifier taxId) {
    Objects.requireNonNull(organizationNamespace, "organizationNamespace must not be null");
    Objects.requireNonNull(taxId, "taxId must not be null");
    return new OrganizationId(NameBasedUuid.v5(organizationNamespace, taxId.derivationKey()));
  }

  public static OrganizationId parse(String value) {
    Objects.requireNonNull(value, "value must not be null");
    try {
      return new OrganizationId(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("organizationId is not a valid UUID");
    }
  }

  /** The scope a ledger account for this organization is derived under. */
  public String scope() {
    return value.toString();
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
