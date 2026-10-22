package dev.fondaria.contribution.batch;

import dev.fondaria.common.NameBasedUuid;
import dev.fondaria.common.OrganizationId;
import java.util.UUID;

/**
 * Derives a batch id from the three values that identify a batch (ADR-0009 as amended): {@code
 * (organizationId, objectKey, checksumSha256)}.
 *
 * <p><b>Derived, never generated.</b> Two concurrent submissions of one file must converge on one
 * batch, and a random id would let them race to create two — the same reasoning that makes ledger
 * transfer ids derived (docs/ENGINEERING-RULES.md §4). Resubmitting the same file therefore lands
 * on the same primary key, and the database refuses the second insert rather than the service
 * having to notice.
 *
 * <p><b>The namespace is the organization's own id.</b> A batch id is neither a ledger id nor an
 * organization id, so neither configured namespace fits it; and an organization id is already a
 * stable, per-tenant UUID that exists for exactly as long as the batches under it. Using it means
 * one tenant's batch id can never collide with another's, and no third namespace has to be
 * configured, documented and then never changed for the life of the system.
 */
public final class ContributionBatchId {

  /**
   * Unit separator: it cannot occur in an object key or a hex checksum, so the join is injective.
   */
  private static final String SEPARATOR = "\u001f";

  private ContributionBatchId() {}

  public static UUID derive(
      OrganizationId organizationId, String objectKey, String checksumSha256) {
    return NameBasedUuid.v5(organizationId.value(), objectKey + SEPARATOR + checksumSha256);
  }

  /**
   * The id of one part of a file too large to allot as a single linked chain (ADR-0017).
   *
   * <p><b>The namespace is the file's own batch id</b>, not the organization's. A part exists only
   * as a slice of one file, and deriving it under that file means the id cannot be reached from
   * anywhere else — there is no string an employer could submit that would land on a part's id, and
   * two files split under the same organization can never collide however similar their contents.
   *
   * <p>Derived for the same reason {@link #derive} is: staging a file is re-runnable, and a part
   * whose id were generated would be created afresh on every attempt, splitting one file's rows
   * across two sets of parts. Derived, a re-run converges on the parts that already exist and the
   * database refuses the duplicates.
   *
   * @param partNumber 1-based, and never 1 here: the submitted row is part 1 and keeps its own
   *     rows, so only parts 2 and above have an id to derive
   */
  public static UUID derivePart(UUID fileBatchId, int partNumber) {
    if (partNumber < 2) {
      throw new IllegalArgumentException(
          "part 1 is the submitted batch itself and has no derived id; partNumber=" + partNumber);
    }
    return NameBasedUuid.v5(fileBatchId, "part" + SEPARATOR + partNumber);
  }
}
