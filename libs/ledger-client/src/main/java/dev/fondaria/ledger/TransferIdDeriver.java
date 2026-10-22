package dev.fondaria.ledger;

import dev.fondaria.common.NameBasedUuid;
import java.util.Objects;
import java.util.UUID;

/**
 * Derives ledger transfer ids deterministically, per Task 2.3:
 *
 * <pre>transfer_id = UUIDv5(tenantNamespace, clientId + ":" + idempotencyKey)</pre>
 *
 * <p>This makes the ledger itself the idempotency authority: resubmitting the same (clientId,
 * idempotencyKey) pair — from this JVM, a different JVM, or a future version of this class — always
 * yields the same {@link TransferId}, so a retried request lands on the same transfer instead of
 * creating a duplicate. Never generate a transfer id randomly in a request path.
 *
 * <p>The tenant namespace is a UUID sourced from configuration by the caller. It must never be
 * hardcoded, and must be stable for the life of the tenant — changing it re-derives every future
 * transfer id under a different namespace, silently breaking idempotency for retries in flight.
 */
public final class TransferIdDeriver {

  private final UUID tenantNamespace;

  public TransferIdDeriver(UUID tenantNamespace) {
    this.tenantNamespace = Objects.requireNonNull(tenantNamespace, "tenantNamespace");
  }

  public TransferId derive(String clientId, String idempotencyKey) {
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    UUID uuid = NameBasedUuid.v5(tenantNamespace, clientId + ":" + idempotencyKey);
    return toTransferId(uuid);
  }

  /**
   * Byte order for the UUID-to-u128 conversion: a {@link TransferId}'s {@code
   * mostSignificantBits}/{@code leastSignificantBits} are exactly {@link UUID}'s own
   * most/least-significant 64 bits (RFC 4122 network byte order, packed big-endian into each long).
   * This is the same layout {@link UUID#UUID(long, long)} and {@link UUID#getMostSignificantBits()}
   * already use, so the conversion is a direct field copy in both directions — see {@link
   * #toUuid(TransferId)} for the inverse.
   */
  public static TransferId toTransferId(UUID uuid) {
    Objects.requireNonNull(uuid, "uuid");
    return TransferId.of(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  public static UUID toUuid(TransferId transferId) {
    Objects.requireNonNull(transferId, "transferId");
    return new UUID(transferId.mostSignificantBits(), transferId.leastSignificantBits());
  }
}
