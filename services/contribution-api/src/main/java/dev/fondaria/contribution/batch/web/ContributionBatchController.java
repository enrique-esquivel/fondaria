package dev.fondaria.contribution.batch.web;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.batch.ContributionBatch;
import dev.fondaria.contribution.batch.ContributionBatchService;
import dev.fondaria.contribution.batch.DealingDateService;
import dev.fondaria.contribution.batch.InvalidBatchSubmissionException;
import dev.fondaria.contribution.batch.ReconciliationAgeingService;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two calls of ADR-0009's transport, plus the batch's status resource and the reconciliation
 * ageing report.
 *
 * <h2>This endpoint ships unauthenticated, and that is a known gap</h2>
 *
 * <p>Same position as the cash-receipt endpoint: authorization is Phase 9's (identity) and Phase
 * 10's (edge), and no scheme is invented here because inventing one would have to be removed. Until
 * then an object key is scoped to its organization by prefix, so one employer cannot submit
 * another's file even without authentication — but the endpoints must not be exposed outside the
 * cluster.
 *
 * <h2>Status codes</h2>
 *
 * <ul>
 *   <li>{@code 200} — an upload slot, a batch status, or the ageing report
 *   <li>{@code 202} — batch received; validation is asynchronous. Also returned for a resubmission
 *       of the same file, carrying the original batch and its current status
 *   <li>{@code 400} — the call is unusable (bad checksum format, foreign object key)
 *   <li>{@code 404} — no such organization, or no such batch under it
 * </ul>
 *
 * <p>There is no {@code 409} and no {@code 422} on submission, and there cannot be. Duplicate
 * suppression is keyed on the object and its checksum, so "same key, different body" is not a state
 * this contract can express — a different body is a different checksum, and a different checksum is
 * a different batch. A resubmission that declares different totals for the same bytes is the same
 * batch and its original declaration stands; the file is what either one of them has to match.
 *
 * <p>Every binding annotation names its parameter explicitly. The build does not compile with
 * {@code -parameters}, so an unnamed {@code @PathVariable} resolves to nothing at runtime and fails
 * every request it appears on — a failure that shows up only when the endpoint is called.
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}")
public class ContributionBatchController {

  private final ContributionBatchService batches;
  private final ReconciliationAgeingService ageing;
  private final DealingDateService dealingDates;

  public ContributionBatchController(
      ContributionBatchService batches,
      ReconciliationAgeingService ageing,
      DealingDateService dealingDates) {
    this.batches = batches;
    this.ageing = ageing;
    this.dealingDates = dealingDates;
  }

  @PostMapping("/contribution-batches/upload-url")
  public ResponseEntity<UploadUrlResponse> createUploadUrl(
      @PathVariable("organizationId") String organizationId,
      @RequestBody UploadUrlRequest request) {
    OrganizationId id = parseId(organizationId);
    PresignedUpload upload =
        batches.createUploadSlot(
            id, request.requirePlanId(), request.requirePayrollPeriod(), request.requireFileName());
    return ResponseEntity.ok(UploadUrlResponse.of(upload));
  }

  /**
   * {@code 202} whether this call created the batch or found it. Deliberately not {@code 201} on
   * first submission and {@code 200} on a repeat, the way the cash-receipt endpoint distinguishes
   * them: nothing has been validated yet either way, so a status code claiming a resource was
   * created would be claiming something this call has not established.
   */
  @PostMapping("/contribution-batches")
  public ResponseEntity<ContributionBatchResponse> submit(
      @PathVariable("organizationId") String organizationId,
      @RequestBody SubmitContributionBatchRequest request) {
    OrganizationId id = parseId(organizationId);
    ContributionBatch batch =
        batches.submit(
            id,
            request.requireObjectKey(),
            request.requireChecksum(),
            request.requireDeclaration());
    return ResponseEntity.accepted()
        .location(
            URI.create(
                "/v1/organizations/" + id.value() + "/contribution-batches/" + batch.batchId()))
        // No dealing-date lookup on submission: this batch is RECEIVED, nothing has
        // reconciled, and DealingDateService would return empty for it anyway. Paying for
        // a plan-version read on the hot submission path to be told "not yet" is waste.
        .body(ContributionBatchResponse.of(batch, Optional.empty()));
  }

  /**
   * Carries the dealing date for a batch awaiting allotment (Task 21.1) — the window between
   * reconciliation and allotment, where the participant's money has arrived and their units have
   * not, reported as pending with the valuation date it will deal at rather than as a status that
   * looks stuck.
   */
  @GetMapping("/contribution-batches/{batchId}")
  public ContributionBatchResponse get(
      @PathVariable("organizationId") String organizationId,
      @PathVariable("batchId") String batchId) {
    OrganizationId id = parseId(organizationId);
    ContributionBatch batch = batches.get(id, parseBatchId(batchId));
    // A file too large to allot in one linked chain is several batches (ADR-0017), so its
    // progress is a count of parts rather than a single status. Read here and not on
    // submission: at submission nothing has been parsed, so nothing has been divided yet.
    return ContributionBatchResponse.of(
        batch, dealingDates.dealingDateFor(batch), batches.progressOf(id, batch.batchId()));
  }

  /**
   * Money with no file and files with no money, both first-class states rather than errors. Per
   * organization: reconciliation is an employer-level conversation, and a platform-wide report
   * would be a different resource with different authorization.
   */
  @GetMapping("/reconciliation/ageing")
  public AgeingReportResponse ageingReport(@PathVariable("organizationId") String organizationId) {
    OrganizationId id = parseId(organizationId);
    return AgeingReportResponse.of(ageing.forOrganization(id));
  }

  private static OrganizationId parseId(String raw) {
    try {
      return OrganizationId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidBatchSubmissionException("organizationId is not a valid UUID");
    }
  }

  private static UUID parseBatchId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidBatchSubmissionException("batchId is not a valid UUID");
    }
  }
}
