package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code workflow.contribution_batch}: a submitted dispersion file and where its
 * validation stands.
 *
 * <p><b>{@link #declaredTotalCents()} is a declaration, not a balance.</b> It is what the employer
 * said it sent. What actually arrived is read from the ledger, receipt by receipt, and the two are
 * compared by the reconciliation gate — they are never assumed equal and this value is never one
 * side of a balance (ADR-0008 invariant 1).
 *
 * @param batchId derived from {@code (organizationId, objectKey, checksumSha256)}, so resubmitting
 *     the same file converges on the same batch rather than racing to create a second one
 * @param declared what the submission said this batch is. Present from the moment the batch exists
 *     — {@code contribution-file-v2.md} §1.1 moved these out of the file — which is why a REJECTED
 *     batch can say which payroll period was refused whatever stage it failed at, and why the
 *     reconciliation gate can re-evaluate a PENDING_RECONCILIATION batch without re-fetching and
 *     re-parsing a file that may run to hundreds of thousands of rows.
 * @param fileValidatedAt when the file was read and passed every structural check, absent until it
 *     has been. Under v1 this fact was implied by the header being present; the declarations now
 *     arrive at submission, and it cannot be read off {@code status} because claiming a batch for a
 *     sweep moves it to {@code VALIDATING} first.
 */
public record ContributionBatch(
    UUID batchId,
    OrganizationId organizationId,
    String objectKey,
    String checksumSha256,
    ContributionBatchStatus status,
    BatchDeclaration declared,
    Optional<BatchRejection> rejection,
    Optional<Instant> fileValidatedAt,
    Optional<Instant> validationStartedAt,
    Optional<Instant> stagedAt,
    Instant submittedAt,
    Instant statusUpdatedAt) {

  public ContributionBatch {
    Objects.requireNonNull(batchId, "batchId must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(objectKey, "objectKey must not be null");
    Objects.requireNonNull(checksumSha256, "checksumSha256 must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(declared, "declared must not be null");
    Objects.requireNonNull(rejection, "rejection must not be null");
    Objects.requireNonNull(fileValidatedAt, "fileValidatedAt must not be null");
    Objects.requireNonNull(validationStartedAt, "validationStartedAt must not be null");
    Objects.requireNonNull(stagedAt, "stagedAt must not be null");
    Objects.requireNonNull(submittedAt, "submittedAt must not be null");
    Objects.requireNonNull(statusUpdatedAt, "statusUpdatedAt must not be null");
    if (rejection.isPresent() != (status == ContributionBatchStatus.REJECTED)) {
      throw new IllegalArgumentException("rejection is present exactly when status is REJECTED");
    }
  }

  /** The declared grand total in MXN cents. A declaration, never a balance. */
  public long declaredTotalCents() {
    return declared.declaredTotalCents();
  }

  /** This batch, now known to have been read and structurally accepted. */
  public ContributionBatch withFileValidatedAt(Instant readAt) {
    return new ContributionBatch(
        batchId,
        organizationId,
        objectKey,
        checksumSha256,
        status,
        declared,
        rejection,
        Optional.of(readAt),
        validationStartedAt,
        stagedAt,
        submittedAt,
        statusUpdatedAt);
  }

  /**
   * Whether this batch is waiting to be allotted: staged, and {@code allotment-job} (Task 21.2) has
   * not priced it yet — {@code STAGED} is the whole answer because allotment moves a batch straight
   * to {@link ContributionBatchStatus#ALLOTTED}, with nothing resting in between. This is the
   * window {@code docs/DOMAIN.md} §5 calls first-class: the participant's money has arrived and
   * reconciled, and they hold no units against it yet. It is a state, not an inconsistency, and
   * Task 6.2 requires it be shown separately at face value.
   */
  public boolean isAwaitingAllotment() {
    return status == ContributionBatchStatus.STAGED;
  }

  /** No object key and no checksum: neither is secret, but neither says anything useful either. */
  @Override
  public String toString() {
    return "ContributionBatch[batchId="
        + batchId
        + ", organizationId="
        + organizationId
        + ", status="
        + status
        + "]";
  }
}
