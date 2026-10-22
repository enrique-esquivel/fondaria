package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.objectstore.ObjectStore;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.contribution.organization.OrganizationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The two calls of ADR-0009's transport: hand out an upload slot, then accept a batch by reference.
 *
 * <h2>Nothing here reads the file</h2>
 *
 * <p>Submission commits a {@code RECEIVED} row and returns. The file's bytes never pass through an
 * API request (ADR-0009), and validating a file that can run to hundreds of thousands of rows
 * inside the request would pin a virtual thread for its whole duration — the coupling that ADR
 * exists to avoid. {@link BatchValidationRunner} picks the batch up afterwards.
 *
 * <h2>Why there is no idempotency key</h2>
 *
 * <p>A batch <i>is</i> {@code (organizationId, objectKey, checksumSha256)}. The batch id is derived
 * from those three values, so resubmitting the same file converges on the same row and the database
 * refuses the duplicate. "Same key, different body" — the 422 the cash-receipt endpoint needs a
 * stored fingerprint to detect — cannot be expressed here at all: a different body is a different
 * checksum, and a different checksum is a different batch.
 */
@Service
public class ContributionBatchService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContributionBatchService.class);

  private static final int MAXIMUM_FILE_NAME = 80;

  private final ContributionBatchRepository batches;
  private final ObjectStore objectStore;
  private final OrganizationService organizations;
  private final Clock clock;

  public ContributionBatchService(
      ContributionBatchRepository batches,
      ObjectStore objectStore,
      OrganizationService organizations,
      Clock clock) {
    this.batches = batches;
    this.objectStore = objectStore;
    this.organizations = organizations;
    this.clock = clock;
  }

  /**
   * Issues a presigned upload slot under a key this service chooses.
   *
   * <p>The caller's file name is carried through for audit and never used as the key: it is
   * caller-controlled text, and a key built from it could name any object in the bucket. A random
   * UUID makes the key unguessable, and the organization prefix is what {@link #submit} later
   * checks a submitted key against.
   *
   * @throws dev.fondaria.contribution.organization.OrganizationNotFoundException checked first, so
   *     an unregistered employer never receives a write grant into the platform's object store
   */
  /**
   * An upload slot whose key carries the plan and payroll period the file is for ({@code
   * contribution-file-v2.md} §1.1).
   *
   * <p><b>The service mints the key, from the same values the submission will declare.</b> That is
   * what keeps an object identifiable years later — pulled out of storage on its own it still says
   * which employer, plan and period it belongs to — without the key ever becoming the authority for
   * any of them. Submission requires the key to sit under the prefix its declared plan and period
   * generate, so the name and the declaration cannot drift apart.
   */
  public PresignedUpload createUploadSlot(
      OrganizationId organizationId, String planId, String payrollPeriod, String fileName) {
    organizations.get(organizationId);
    String key =
        prefix(organizationId, planId, payrollPeriod)
            + UUID.randomUUID()
            + "-"
            + sanitize(fileName);
    LOGGER.info("Issued a contribution-file upload slot, organizationId={}", organizationId);
    return objectStore.presignUpload(key);
  }

  /**
   * Accepts a batch by reference, or returns the batch this file already is.
   *
   * @throws dev.fondaria.contribution.organization.OrganizationNotFoundException no organization is
   *     registered under this id
   * @throws InvalidBatchSubmissionException the object key does not belong to this organization
   */
  public ContributionBatch submit(
      OrganizationId organizationId,
      String objectKey,
      String checksumSha256,
      BatchDeclaration declared) {
    organizations.get(organizationId);
    requireOwnKey(organizationId, declared, objectKey);

    Instant now = clock.instant();
    ContributionBatch submitted =
        batches.submit(
            new ContributionBatch(
                ContributionBatchId.derive(organizationId, objectKey, checksumSha256),
                organizationId,
                objectKey,
                checksumSha256,
                ContributionBatchStatus.RECEIVED,
                declared,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now));
    LOGGER.info(
        "Contribution batch submitted, batchId={}, organizationId={}, status={}",
        submitted.batchId(),
        organizationId,
        submitted.status());
    return submitted;
  }

  public ContributionBatch get(OrganizationId organizationId, UUID batchId) {
    return batches
        .find(organizationId, batchId)
        .orElseThrow(() -> new ContributionBatchNotFoundException(batchId));
  }

  /**
   * Where this batch sits among its file's parts (ADR-0017), for the status resource.
   *
   * <p>A separate call rather than a field on {@link ContributionBatch}: it is an aggregate over
   * sibling rows that changes as allotment proceeds, and putting it on the batch would mean every
   * path that reads a batch — the validation sweep among them — paying for a count it never uses.
   */
  public Optional<BatchPartProgress> progressOf(OrganizationId organizationId, UUID batchId) {
    return batches.progressOf(organizationId, batchId);
  }

  /**
   * A submitted key must sit under the organization's own prefix.
   *
   * <p>Cheap, and it closes a real hole: without it one employer could submit another's object key
   * and learn from the resulting status whether that file exists and parses. The header check
   * (check 3) would still refuse to process it, but only after the file had been read — this
   * refuses before anything is fetched.
   */
  private void requireOwnKey(
      OrganizationId organizationId, BatchDeclaration declared, String objectKey) {
    if (!objectKey.startsWith(
        prefix(organizationId, declared.planId(), declared.payrollPeriod()))) {
      throw new InvalidBatchSubmissionException(
          "objectKey does not belong to this organization, plan and payroll period; use a key"
              + " from the upload-url call for the same three");
    }
  }

  /**
   * {@code contribution-batches/{organizationId}/{planId}/{payrollPeriod}/}.
   *
   * <p>The organization segment is the tenant boundary this service enforces without authentication
   * (Phase 9/10's work); the plan and period segments make an object identifiable on its own and
   * are what tie a key to the declaration submitted with it. Both are sanitized the same way a file
   * name is: an employer-supplied string can never change what a key means.
   */
  private static String prefix(OrganizationId organizationId, String planId, String payrollPeriod) {
    return "contribution-batches/"
        + organizationId.value()
        + "/"
        + sanitize(planId)
        + "/"
        + sanitize(payrollPeriod)
        + "/";
  }

  /**
   * Reduced to characters that cannot change what an object key means. Anything else — a slash, a
   * {@code ..}, a control character, a percent escape — is replaced rather than rejected: the name
   * is decoration on a key this service already made unique, so refusing an employer's upload over
   * a space in a filename would be pedantry.
   */
  private static String sanitize(String fileName) {
    String trimmed = fileName == null ? "" : fileName.trim();
    if (trimmed.isEmpty()) {
      return "contribution-file";
    }
    StringBuilder sanitized = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length() && sanitized.length() < MAXIMUM_FILE_NAME; i++) {
      char c = trimmed.charAt(i);
      boolean safe =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '-'
              || c == '_';
      // A second consecutive dot becomes a dash, so the result can never contain "..".
      // Nothing here is a filesystem path and every slash is already gone, so a dot-dot
      // could not traverse anything — but a key that cannot contain one at all needs no
      // reader to reason about what some proxy or client might normalize on the way.
      boolean dotAfterDot =
          c == '.' && !sanitized.isEmpty() && sanitized.charAt(sanitized.length() - 1) == '.';
      sanitized.append(safe && !dotAfterDot ? c : '-');
    }
    return sanitized.toString();
  }
}
