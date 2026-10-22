package dev.fondaria.ledger;

import dev.fondaria.common.NameBasedUuid;
import java.util.Objects;
import java.util.UUID;

/**
 * A 128-bit reference, carried on a transfer, to something that exists outside the ledger — a bank
 * statement line, a payroll file, a remittance advice.
 *
 * <p>It exists so that a movement can be tied back to its external cause without the request
 * handler writing to a second store. A handler writes to exactly one system of record
 * (docs/ENGINEERING-RULES.md §4), so anything that must survive alongside a transfer has to travel
 * <b>on</b> the transfer; and because it is on the transfer, it reaches the projection through CDC
 * for free rather than needing a parallel write that could diverge.
 *
 * <p><b>128 bits holds a derived reference, not the text it came from.</b> A SPEI {@code clave de
 * rastreo} does not fit in sixteen bytes, so callers derive: {@link #derive(UUID, String)} hashes
 * the external identifier into this value. Tie-out therefore works by recomputation — take a line
 * off the bank statement, derive it, and look the transfer up — rather than by reading the original
 * string back out. That direction is the one reconciliation actually needs, and it keeps an
 * external identifier that may itself be sensitive out of the ledger in plaintext.
 *
 * <p>Zero means absent, matching the underlying ledger's own convention for an unset reference.
 */
public record ExternalReference(long mostSignificantBits, long leastSignificantBits) {

  /** The absent reference. A transfer carrying this has no external cause recorded. */
  public static final ExternalReference NONE = new ExternalReference(0L, 0L);

  public static ExternalReference of(long mostSignificantBits, long leastSignificantBits) {
    return new ExternalReference(mostSignificantBits, leastSignificantBits);
  }

  /**
   * Derives a reference from an external identifier. Deterministic: the same identifier always
   * derives the same reference, which is what makes tie-out by recomputation possible.
   *
   * @param namespace a UUID from configuration, stable for the life of the ledger
   * @param externalId the external identifier, e.g. a bank statement reference
   */
  public static ExternalReference derive(UUID namespace, String externalId) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(externalId, "externalId must not be null");
    if (externalId.isBlank()) {
      throw new IllegalArgumentException("externalId must not be blank");
    }
    UUID uuid = NameBasedUuid.v5(namespace, externalId);
    return new ExternalReference(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
  }

  public boolean isPresent() {
    return mostSignificantBits != 0L || leastSignificantBits != 0L;
  }
}
