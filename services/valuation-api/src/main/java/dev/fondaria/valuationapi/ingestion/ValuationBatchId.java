package dev.fondaria.valuationapi.ingestion;

import dev.fondaria.common.NameBasedUuid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives a batch id from the three values that identify a valuation batch: {@code (source,
 * objectKey, checksumSha256)}.
 *
 * <p><b>Derived, never generated</b>, for the reason {@code ContributionBatchId} is: two concurrent
 * submissions of one file must converge on one batch, and a random id would let them race to create
 * two (AGENTS.md §4). This matters more here than it does for contributions — providers redeliver
 * valuation files as a matter of routine, so resubmission is the normal path rather than an edge
 * case, and a fresh batch per delivery would accumulate one row per redelivery of the same numbers.
 *
 * <p><b>The namespace is derived from the source.</b> A valuation batch is neither a ledger id nor
 * an organization id, and unlike a contribution batch it has no tenant to borrow a namespace from —
 * a valuation is per fund, and one file may carry many funds across many tenants. Hashing the
 * provider's name into a stable namespace UUID gives each provider its own id space without a third
 * configured namespace that would have to be documented and then never changed for the life of the
 * system.
 */
public final class ValuationBatchId {

  /**
   * The fixed root this service hashes provider names under. A constant rather than configuration:
   * changing it would re-derive every existing batch id, which is exactly the property that must
   * never change — the same reasoning that pins the organization and ledger namespaces as
   * deployment configuration set once and never altered.
   */
  private static final UUID SOURCE_NAMESPACE =
      UUID.nameUUIDFromBytes("fondaria.valuation.source".getBytes(StandardCharsets.UTF_8));

  private ValuationBatchId() {}

  /**
   * <b>The checksum leads, and that is what makes the join injective without a separator.</b> A
   * submitted checksum is always exactly 64 hex characters (validated at the edge before this is
   * called), so the boundary between the two values is at a fixed offset and no object key can
   * shift it. Concatenating the other way round would need a delimiter, and every candidate
   * delimiter is a character an S3 object key is allowed to contain.
   */
  public static UUID derive(String source, String objectKey, String checksumSha256) {
    return NameBasedUuid.v5(namespaceFor(source), checksumSha256 + objectKey);
  }

  /**
   * A stable per-provider namespace. Version 5 over the provider's name in a fixed namespace, so
   * the same provider always yields the same namespace and two providers never share one.
   */
  private static UUID namespaceFor(String source) {
    return NameBasedUuid.v5(SOURCE_NAMESPACE, source);
  }
}
