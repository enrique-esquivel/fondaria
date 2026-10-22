package dev.fondaria.common;

import java.util.Objects;
import java.util.UUID;

/**
 * A compact, derived reference to the client organization ("tenant") whose activity caused a ledger
 * movement. Carried in a TigerBeetle transfer's and account's {@code user_data_64} slot so that the
 * CDC stream — and therefore every projection built from it — can be partitioned tenant-first
 * ({@code ADR-0005}) without a registry lookup.
 *
 * <p><b>Why this exists.</b> TigerBeetle has one structural partition, {@code ledger}, and {@code
 * ADR-0005} deliberately assigned it to <i>funds</i> (funds are pooled across tenants). A transfer
 * carries no other tenant dimension, and the Redpanda keys are the bare {@code transfer_id} /
 * {@code account_id} (phase-04). So the tenant travels in the payload, in {@code user_data_64},
 * which nothing else uses. {@code ADR-0005}'s "keys carry the tenant prefix" clause is superseded
 * by this — see its 2026-08-28 amendment.
 *
 * <p><b>Derived, not assigned.</b> {@code ref = high 64 bits of UUIDv5(ledgerNamespace,
 * organizationId.scope())} — the same {@link NameBasedUuid} derivation every other id on the
 * platform uses. Write side and read side (the Query API, resolving an {@code organizationId} back
 * to its ref) both compute it as a pure function; there is no {@code organization → ordinal} table
 * to keep in sync or restore. 64 bits keeps the birthday-collision probability negligible (~3e-13
 * at 10,000 tenants); a collision here is unrepairable — the ledger value <i>is</i> the source of
 * truth on replay — which is why it is not narrowed to 32.
 *
 * <p><b>{@link #NONE}.</b> {@code value == 0} is TigerBeetle's own "unset" and means "no tenant": a
 * pooled, fund-level account ({@code FUND_CASH}, {@code UNITS_IN_ISSUE}, {@code
 * ALLOTMENT_SUSPENSE}) genuinely belongs to no one organization. A <i>movement</i> between pooled
 * accounts still carries the causing tenant — the batch's organization — so a real row in {@code
 * projection.transfer} never has {@code NONE}.
 *
 * <p>The {@code long} holds the raw 64-bit pattern and may be negative when read as signed; treat
 * it as unsigned. {@link #asUnsignedDecimal()} is the canonical text form, matching how {@code
 * LedgerEventDecoder} already carries {@code userData64}.
 */
public record TenantRef(long value) {

  /** No tenant — TigerBeetle's "unset" {@code user_data_64}. Only valid on a pooled account. */
  public static final TenantRef NONE = new TenantRef(0L);

  public boolean isNone() {
    return value == 0L;
  }

  /**
   * The tenant ref for one organization under a given ledger namespace.
   *
   * @param ledgerNamespace the same namespace {@code AccountId} / {@code TransferId} derive under
   *     (from {@code IdentityProperties}); must be stable for the life of the ledger
   * @param organizationId the organization whose activity a movement is attributed to
   */
  public static TenantRef derive(UUID ledgerNamespace, OrganizationId organizationId) {
    Objects.requireNonNull(ledgerNamespace, "ledgerNamespace must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    UUID derived = NameBasedUuid.v5(ledgerNamespace, organizationId.scope());
    return new TenantRef(derived.getMostSignificantBits());
  }

  /** The unsigned base-10 form — how {@code user_data_64} is carried on the CDC stream. */
  public String asUnsignedDecimal() {
    return Long.toUnsignedString(value);
  }

  /** Parse the unsigned base-10 form, e.g. a {@code LedgerTransferEvent#userData64()}. */
  public static TenantRef fromUnsignedDecimal(String unsignedDecimal) {
    Objects.requireNonNull(unsignedDecimal, "unsignedDecimal must not be null");
    try {
      return new TenantRef(Long.parseUnsignedLong(unsignedDecimal));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not an unsigned 64-bit integer: " + unsignedDecimal, e);
    }
  }

  @Override
  public String toString() {
    return "TenantRef(" + asUnsignedDecimal() + ")";
  }
}
