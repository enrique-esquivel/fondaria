package dev.fondaria.contribution.batch;

import dev.fondaria.contribution.config.BatchValidationProperties;
import dev.fondaria.contribution.objectstore.ObjectStore;
import dev.fondaria.contribution.objectstore.ObjectStoreUnavailableException;
import dev.fondaria.contribution.objectstore.ObjectTooLargeException;
import dev.fondaria.contribution.participant.ParticipantEnrolmentService;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The asynchronous half of ADR-0009: submission returns {@code 202}, and this runs afterwards.
 *
 * <h2>Why a claimed row rather than a thread</h2>
 *
 * <p>A sweep claims batches by moving them to {@code VALIDATING} and stamping a lease. Handing the
 * work to an executor at submission time would be less machinery and strictly worse: a process that
 * dies mid-validation would strand its batch with nothing able to pick it up, and no second
 * instance could help. A lease in a column is recoverable by any instance, and re-validating is
 * safe because it is a pure function of the file's own bytes.
 *
 * <h2>Two entry paths, on purpose</h2>
 *
 * <ul>
 *   <li>A batch that has not been read yet: fetch, verify the checksum, parse, check the file
 *       against what the submission declared, then the gate.
 *   <li>A batch already in {@code PENDING_RECONCILIATION} has passed all of that; only its cash was
 *       missing. It runs the gate alone. Its declarations arrived with the submission and are on
 *       the row, precisely so this does not re-fetch and re-parse a hundred-thousand-row file every
 *       few seconds for as long as an employer's wire is outstanding.
 * </ul>
 *
 * <h2>Auto-enrolment and staging both run here too</h2>
 *
 * <p>Once a file passes every structural and plan-rule check — the same point {@code
 * fileValidatedAt} is set — its rows are handed to {@link ParticipantEnrolmentService} and then to
 * {@link ContributionStagingService}. Neither waits on the gate: a batch can sit in {@code
 * PENDING_RECONCILIATION} for days while its cash is outstanding, and there is no reason a payroll
 * reference or a staged amount should wait on a wire to be recorded. See {@link
 * ParticipantEnrolmentService} and {@link ContributionStagingService} for why re-running this on
 * the same rows is safe.
 */
@Component
public class BatchValidationRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BatchValidationRunner.class);

  private final ContributionBatchRepository batches;
  private final CashClaimRepository claims;
  private final ObjectStore objectStore;
  private final ReconciliationGate gate;
  private final ContributionBatchValidator validator;
  private final ParticipantEnrolmentService enrolment;
  private final ContributionStagingService staging;
  private final BatchValidationProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public BatchValidationRunner(
      ContributionBatchRepository batches,
      CashClaimRepository claims,
      ObjectStore objectStore,
      ReconciliationGate gate,
      ContributionBatchValidator validator,
      ParticipantEnrolmentService enrolment,
      ContributionStagingService staging,
      BatchValidationProperties properties,
      TransactionTemplate transactions,
      Clock clock) {
    this.batches = batches;
    this.claims = claims;
    this.objectStore = objectStore;
    this.gate = gate;
    this.validator = validator;
    this.enrolment = enrolment;
    this.staging = staging;
    this.properties = properties;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * One validation sweep. Scheduled, and also callable directly — a test drives it explicitly
   * rather than sleeping until a timer happens to fire.
   *
   * @return how many batches this sweep resolved
   */
  @Scheduled(
      fixedDelayString = "${fondaria.batch-validation.poll-interval:5s}",
      initialDelayString = "${fondaria.batch-validation.poll-interval:5s}")
  public int sweep() {
    Instant now = clock.instant();
    List<ContributionBatch> claimed =
        batches.claimForValidation(now, now.minus(properties.staleAfter()), properties.batchSize());
    int resolved = 0;
    for (ContributionBatch batch : claimed) {
      if (validate(batch)) {
        resolved++;
      }
    }
    return resolved;
  }

  /**
   * @return whether this batch reached an outcome. {@code false} means it stays claimed until its
   *     lease goes stale — which is the intended behaviour for a fault on our side, and is also the
   *     backoff: retrying an unreachable object store every few seconds would achieve nothing but
   *     load.
   */
  private boolean validate(ContributionBatch batch) {
    try {
      // Not read off `status`: claiming a batch for this sweep already moved it to
      // VALIDATING, so the status no longer says where it came from. fileValidatedAt is
      // the fact itself, and it is what keeps a PENDING_RECONCILIATION batch from
      // re-fetching and re-parsing its file on every sweep.
      ContributionBatch read = batch;
      if (batch.fileValidatedAt().isEmpty()) {
        readAndCheckFile(batch);
        Instant validatedAt = clock.instant();
        if (!batches.markFileValidated(batch.batchId(), validatedAt)) {
          LOGGER.warn(
              "Lost validation lease while completing structural validation, batchId={},"
                  + " organizationId={}",
              batch.batchId(),
              batch.organizationId());
          return false;
        }
        read = batch.withFileValidatedAt(validatedAt);
      }
      return reconcile(read);
    } catch (BatchRejectedException e) {
      LOGGER.info(
          "Contribution batch rejected, batchId={}, organizationId={}, code={}",
          batch.batchId(),
          batch.organizationId(),
          e.rejection().code());
      return batches.resolve(
          withOutcome(batch, ContributionBatchStatus.REJECTED, Optional.of(e.rejection())));
    } catch (ObjectStoreUnavailableException e) {
      // Not a rejection. The employer's file may be perfect; we could not read it. Telling
      // them their file was wrong would be a lie, and a durable one — REJECTED is terminal.
      LOGGER.error(
          "Could not read a contribution file; the batch stays claimed until its lease expires,"
              + " batchId={}, organizationId={}",
          batch.batchId(),
          batch.organizationId(),
          e);
      return false;
    } catch (RuntimeException e) {
      LOGGER.error(
          "Unexpected failure validating a contribution batch; it will be retried,"
              + " batchId={}, organizationId={}",
          batch.batchId(),
          batch.organizationId(),
          e);
      return false;
    }
  }

  /**
   * Checks 1 and 2 of {@code contribution-file-v2.md} §4, then checks 3 to 8, then auto-enrolment
   * and staging — a file that fails any check above is rejected before a single participant is
   * touched or a single row is staged.
   */
  private void readAndCheckFile(ContributionBatch batch) {
    byte[] bytes = fetch(batch);
    requireChecksumMatches(batch, bytes);
    ContributionFile file = ContributionFileParser.parse(bytes);
    validator.validate(batch.organizationId(), file, batch.declared());
    enrolment.enrol(batch.organizationId(), batch.declared().planId(), file);
    staging.stage(batch, file);
  }

  private byte[] fetch(ContributionBatch batch) {
    try {
      return objectStore
          .fetch(batch.objectKey())
          .orElseThrow(
              () ->
                  new BatchRejectedException(
                      BatchRejection.of(
                          BatchRejectionCode.CHECKSUM_MISMATCH,
                          "No file has been uploaded to the object key this batch was submitted"
                              + " with. Upload the file, then submit it again.")));
    } catch (ObjectTooLargeException e) {
      throw new BatchRejectedException(
          BatchRejection.of(
              BatchRejectionCode.MALFORMED_FILE,
              "The uploaded file is larger than this platform accepts.",
              Map.of("maximumBytes", e.maximumBytes())));
    }
  }

  /**
   * Check 1, before a single row is parsed — a file whose bytes did not arrive intact is not worth
   * reading, and a checksum computed over the stored object is the only way to know they did
   * ({@code contribution-file-v2.md} §4).
   *
   * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: it is constant-time, and
   * comparing a caller-supplied value against a computed one is exactly where that habit belongs,
   * whether or not this particular comparison is attackable.
   */
  private void requireChecksumMatches(ContributionBatch batch, byte[] bytes) {
    String actual = sha256Hex(bytes);
    if (!MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.US_ASCII),
        batch.checksumSha256().getBytes(StandardCharsets.US_ASCII))) {
      // Neither checksum is rendered to the employer: the submitted one they already have,
      // and the computed one says only that the bytes differ, which the code already says.
      throw new BatchRejectedException(
          BatchRejection.of(
              BatchRejectionCode.CHECKSUM_MISMATCH,
              "The uploaded file's SHA-256 is not the checksum submitted with it. Re-upload the"
                  + " file and submit the checksum of the bytes actually sent."));
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * The gate, the claim it implies, and staging, in one transaction.
   *
   * <p>The ledger is read inside that transaction — the same trade {@code CashReceiptService}
   * makes, and for the same reason: the alternative is claiming cash outside the transaction that
   * records the batch as staged, which invents a window where this batch's cash has been matched
   * and claimed but nothing says so, or claimed and attached to a batch that never got marked.
   * Neither is recoverable by inspection afterwards.
   *
   * <p>Both branches below write {@link ContributionBatchStatus#STAGED} directly, never {@link
   * ContributionBatchStatus#VALIDATED}. The rows a STAGED batch stages were already captured by
   * {@link ContributionStagingService} at structural-validation time, so nothing here needs to
   * touch the object store or wait on anything further — the moment this batch's cash is confirmed
   * matched, "validated" and "staged" are the same instant.
   */
  private boolean reconcile(ContributionBatch batch) {
    ReconciliationGate.Outcome outcome =
        gate.evaluate(batch.organizationId(), batch.batchId(), batch.declaredTotalCents());

    if (outcome instanceof ReconciliationGate.Outcome.AlreadyFunded funded) {
      // A human assigned this batch's cash to break a tie the gate refused to guess at.
      // Nothing to claim — the rows are already there — so this is a status change alone,
      // and it must not go through the claim path, which would insert them a second time.
      LOGGER.info(
          "Contribution batch staged against cash already assigned to it, batchId={},"
              + " organizationId={}, receiptsHeld={}",
          batch.batchId(),
          batch.organizationId(),
          funded.receipts().size());
      // A transaction even though only statuses move: the file and its parts (ADR-0017)
      // become dealable together or not at all. A file staged with its parts left behind
      // would hold an employer's whole wire against rows nothing would ever allot.
      return Boolean.TRUE.equals(
          transactions.execute(
              status ->
                  stageFileAndParts(
                      withOutcome(batch, ContributionBatchStatus.STAGED, Optional.empty()))));
    }

    if (outcome instanceof ReconciliationGate.Outcome.Matched matched) {
      try {
        Boolean written =
            transactions.execute(
                status -> {
                  claims.claim(
                      batch.organizationId(), batch.batchId(), matched.receipts(), clock.instant());
                  boolean resolved =
                      stageFileAndParts(
                          withOutcome(batch, ContributionBatchStatus.STAGED, Optional.empty()));
                  if (!resolved) {
                    // The lease went stale and another sweep owns this batch now. Its
                    // claim must go back with it, or this batch would hold money no
                    // status says it holds.
                    status.setRollbackOnly();
                  }
                  return resolved;
                });
        if (Boolean.TRUE.equals(written)) {
          LOGGER.info(
              "Contribution batch reconciled and staged, batchId={}, organizationId={},"
                  + " receiptsClaimed={}",
              batch.batchId(),
              batch.organizationId(),
              matched.receipts().size());
          for (TransferId receipt : matched.receipts()) {
            LOGGER.info(
                "Cash receipt claimed by a contribution batch, transferId={}, batchId={}",
                TransferIdDeriver.toUuid(receipt),
                batch.batchId());
          }
        }
        return Boolean.TRUE.equals(written);
      } catch (DuplicateKeyException e) {
        // Another batch claimed one of these receipts between the gate reading them and
        // this claim. Nothing was taken — the whole insert rolled back — so the batch
        // simply waits and the next sweep re-evaluates against what is left.
        LOGGER.warn(
            "A receipt this batch matched was claimed by another batch; it stays pending,"
                + " batchId={}, organizationId={}",
            batch.batchId(),
            batch.organizationId());
      }
    }

    return batches.resolve(
        withOutcome(batch, ContributionBatchStatus.PENDING_RECONCILIATION, Optional.empty()));
  }

  /**
   * Stages a file and every part of it (ADR-0017) at the same instant.
   *
   * <p>The cascade runs only when the file's own status write landed. If the lease went stale and
   * another sweep owns this batch, {@link ContributionBatchRepository#resolve} returns {@code
   * false}, and moving its parts anyway would make them dealable against a decision this pass was
   * not entitled to make.
   *
   * <p>A file that was never split cascades to nothing, which costs one no-op {@code UPDATE} and
   * keeps a single path for both shapes — the alternative is a branch on "was this split", asked in
   * the one place where getting it wrong strands an employer's rows.
   */
  private boolean stageFileAndParts(ContributionBatch staged) {
    if (!batches.resolve(staged)) {
      return false;
    }
    Instant stagedAt =
        staged
            .stagedAt()
            .orElseThrow(
                () -> new IllegalStateException("A STAGED batch must carry the instant it staged"));
    int parts = batches.stageParts(staged.batchId(), stagedAt, staged.statusUpdatedAt());
    if (parts > 0) {
      LOGGER.info(
          "Contribution file staged in parts, batchId={}, organizationId={}, partsStaged={}",
          staged.batchId(),
          staged.organizationId(),
          parts);
    }
    return true;
  }

  /**
   * The one place a batch's outcome is stamped — and therefore the one place {@code stagedAt} is
   * set.
   *
   * <p><b>Write-once, on the way in as well as in the repository.</b> {@code stagedAt} is what
   * cut-off is evaluated against (Task 21.1), so a batch that reaches STAGED a second time — a
   * stale-lease re-sweep, an operator-assigned re-resolve — must keep the instant it first became
   * dealable. Recomputing it here would silently move an unallotted batch's dealing date forward
   * every time the poller looked at it, which is how a participant ends up priced at a NAV struck
   * days after their money was known to be there.
   */
  private ContributionBatch withOutcome(
      ContributionBatch batch, ContributionBatchStatus status, Optional<BatchRejection> rejection) {
    Instant now = clock.instant();
    Optional<Instant> stagedAt =
        status == ContributionBatchStatus.STAGED
            ? batch.stagedAt().or(() -> Optional.of(now))
            : batch.stagedAt();
    return new ContributionBatch(
        batch.batchId(),
        batch.organizationId(),
        batch.objectKey(),
        batch.checksumSha256(),
        status,
        batch.declared(),
        rejection,
        batch.fileValidatedAt(),
        Optional.empty(),
        stagedAt,
        batch.submittedAt(),
        now);
  }
}
