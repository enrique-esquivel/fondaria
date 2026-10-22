package dev.fondaria.ledger;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Derives the {@link TenantRef} an organization's ledger movements and per-holder accounts carry in
 * {@code user_data_64} (ADR-0005, 2026-08-28 amendment).
 *
 * <p>Wired as a bean from the same {@code LEDGER_ID_NAMESPACE} as {@link AccountIdDeriver} and
 * {@link TransferIdDeriver} — a tenant ref that used a different namespace would not match the
 * account and transfer ids already derived for that organization. This class is the one place a
 * service turns an {@link OrganizationId} into a ref, so the derivation cannot drift between call
 * sites.
 */
public final class TenantRefDeriver {

  private final UUID ledgerNamespace;

  public TenantRefDeriver(UUID ledgerNamespace) {
    this.ledgerNamespace = Objects.requireNonNull(ledgerNamespace, "ledgerNamespace");
  }

  /** The ref for one organization. Stable for the life of the ledger. */
  public TenantRef derive(OrganizationId organizationId) {
    return TenantRef.derive(ledgerNamespace, organizationId);
  }
}
