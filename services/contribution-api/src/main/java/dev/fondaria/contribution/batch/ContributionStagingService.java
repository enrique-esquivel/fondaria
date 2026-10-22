package dev.fondaria.contribution.batch;

import dev.fondaria.contribution.config.BatchValidationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Staging for allotment — Task 3.6, extended by ADR-0017 to divide a file into parts.
 *
 * <p>Called from {@code BatchValidationRunner}'s path, at the same point auto-enrolment runs: after
 * a file has passed every structural and plan-rule check of {@code contribution-file-v2.md} §4, and
 * before the reconciliation gate. A batch that fails one of those checks is rejected whole and
 * stages nothing, the same "never a partial allotment" docs/ENGINEERING-RULES.md §1 states for
 * money.
 *
 * <p><b>Why this runs before the gate, not after.</b> By the time a batch reaches this point, no
 * further check can reject it — every remaining step is the gate deciding whether its cash has
 * arrived, which is independent of the file. So a row captured here is not "staged too early": it
 * is a fact about the file that is already permanent, and it saves {@code BatchValidationRunner}
 * from re-fetching and re-parsing a hundred-thousand-row file the moment the gate finally passes,
 * exactly the trade {@code fileValidatedAt} already makes for structural validation itself. The row
 * only enters "the staged set" once its batch's status says so — {@link StagedRowRepository#find}
 * joins against {@code workflow.contribution_batch.status} — so nothing here is visible as staged
 * ahead of that decision.
 *
 * <h2>A file larger than one linked chain becomes several batches (ADR-0017)</h2>
 *
 * <p>Allotment posts one linked chain per batch, and a chain cannot exceed {@code
 * LedgerLimits.MAX_LINKED_CHAIN_LENGTH} transfers. Rather than splitting the chain — which would
 * give up the atomicity the whole design rests on — the batch becomes the unit of size: {@link
 * BatchPartitioner} divides the rows, part 1's rows stay on the submitted batch, and each further
 * part is inserted here as its own {@code workflow.contribution_batch} row carrying its own rows.
 *
 * <p><b>A part is never gated on its own.</b> It is created {@code PENDING_RECONCILIATION} with its
 * file as parent, which is precisely what excludes it from the validation sweep and the ageing
 * report; it moves to {@code STAGED} only when its file's cash reconciles, in the same transaction
 * that stages the file. The employer still funds once, against one declared total, and each part
 * then draws its own share of {@code EMPLOYER_COLLECTIONS} when its chain posts.
 *
 * <h2>Idempotent by construction, and now atomic as well</h2>
 *
 * <p>Staging reduces to {@link StagedRowRepository#insertIfAbsent}, keyed on {@code
 * (organizationId, batchId, participantInternalRef)}, and to {@link
 * ContributionBatchRepository#insertPartIfAbsent}, keyed on a part id derived from {@code (fileId,
 * partNumber)}. So re-sweeping a batch — or a lease surviving a crash and being retried — records
 * each row and each part at most once whether this method runs once or five times against the same
 * file.
 *
 * <p>That key only protects a row that lands in the <b>same part</b> on every attempt, which is why
 * the whole file is staged in one transaction. The partition is a pure function of the file and the
 * row cap, so two attempts agree — unless the cap were changed between them, and a half-applied old
 * partition meeting a new one would stage a participant twice, under two different parts, which is
 * a doubled contribution. One transaction means a re-run always starts from nothing or from a
 * complete, consistent staging.
 */
@Component
public class ContributionStagingService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContributionStagingService.class);

  private final StagedRowRepository repository;
  private final ContributionBatchRepository batches;
  private final BatchValidationProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public ContributionStagingService(
      StagedRowRepository repository,
      ContributionBatchRepository batches,
      BatchValidationProperties properties,
      TransactionTemplate transactions,
      Clock clock) {
    this.repository = repository;
    this.batches = batches;
    this.properties = properties;
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * @param batch the submitted file, mid-validation. It is part 1 and keeps part 1's rows, so a
   *     file small enough to allot in one chain produces no part rows at all and behaves exactly as
   *     it did before ADR-0017.
   * @param file a file that has already passed {@code ContributionFileParser} and {@code
   *     ContributionBatchValidator}
   * @return how many parts the file was divided into — 1 when it was not divided
   */
  public int stage(ContributionBatch batch, ContributionFile file) {
    List<List<ContributionFile.ParticipantRow>> parts =
        BatchPartitioner.partition(file.rows(), properties.maxRowsPerPart());

    // Never re-stage rows already linked-chained into the ledger (Task 21.6).
    Optional<BatchPartProgress> progress =
        batches.progressOf(batch.organizationId(), batch.batchId());
    if (progress.isPresent() && progress.get().allottedParts() > 0) {
      LOGGER.warn(
          "Contribution batch or its parts are already allotted in the ledger; skipping staging,"
              + " organizationId={}, batchId={}, allottedParts={}",
          batch.organizationId(),
          batch.batchId(),
          progress.get().allottedParts());
      return parts.size();
    }

    Instant now = clock.instant();
    transactions.executeWithoutResult(status -> stageParts(batch, parts, now));

    // Counts only. Neither an internal ref nor a payroll ref reaches a log
    // (docs/ENGINEERING-RULES.md §7) --
    // every field of a staged row but the amounts identifies a person.
    LOGGER.info(
        "Contribution batch rows captured for staging, organizationId={}, batchId={},"
            + " rowsSeen={}, parts={}",
        batch.organizationId(),
        batch.batchId(),
        file.rows().size(),
        parts.size());
    return parts.size();
  }

  private void stageParts(
      ContributionBatch batch, List<List<ContributionFile.ParticipantRow>> parts, Instant now) {
    for (int index = 0; index < parts.size(); index++) {
      int partNumber = index + 1;
      List<ContributionFile.ParticipantRow> rows = parts.get(index);
      UUID partBatchId =
          partNumber == 1
              ? batch.batchId()
              : ContributionBatchId.derivePart(batch.batchId(), partNumber);

      Optional<ContributionBatch> existingPart =
          partNumber == 1 ? Optional.of(batch) : batches.find(batch.organizationId(), partBatchId);
      if (existingPart.isPresent()
          && existingPart.get().status() == ContributionBatchStatus.ALLOTTED) {
        LOGGER.warn(
            "Part {} of batch {} is already ALLOTTED; skipping row staging",
            partNumber,
            batch.batchId());
        continue;
      }

      if (partNumber > 1) {
        batches.insertPartIfAbsent(
            partOf(batch, partBatchId, rows, now), batch.batchId(), partNumber);
      }

      for (ContributionFile.ParticipantRow row : rows) {
        repository.insertIfAbsent(
            new StagedRow(
                batch.organizationId(),
                partBatchId,
                row.participantInternalRef(),
                row.participantPayrollRef(),
                batch.declared().planId(),
                batch.declared().payrollPeriod(),
                batch.declared().valueDate(),
                row.amounts(),
                now));
      }
    }
  }

  /**
   * A part, as a batch in its own right.
   *
   * <p>It carries its file's object key and checksum because that is the truth — it is that file —
   * and the content-address uniqueness that would otherwise collide is scoped to submitted files
   * alone (V24). Its totals are its own rows' sums rather than anything an employer declared: a
   * part declares nothing, and no reconciliation gate ever reads them. They are stored because the
   * columns are what a part's own status endpoint and any later audit read to say what this part
   * contains, and because {@code workflow.contribution_batch}'s CHECK constraints require a
   * reconcilable row to carry a total.
   *
   * <p>{@code fileValidatedAt} is set: the file behind this part has been read and has passed every
   * structural check, and leaving it empty would invite a sweep to re-fetch and re-parse it. {@code
   * stagedAt} is deliberately absent — a part is not dealable until its file's cash reconciles, and
   * the cut-off that decides its dealing date (Task 21.1) is stamped by that cascade, not here.
   */
  private ContributionBatch partOf(
      ContributionBatch batch,
      UUID partBatchId,
      List<ContributionFile.ParticipantRow> rows,
      Instant now) {
    Map<ContributionSource, Long> sourceTotals = new EnumMap<>(ContributionSource.class);
    long total = 0L;
    for (ContributionFile.ParticipantRow row : rows) {
      for (ContributionSource source : ContributionSource.values()) {
        long amount = row.amountOf(source);
        sourceTotals.merge(source, amount, Math::addExact);
        total = Math.addExact(total, amount);
      }
    }

    return new ContributionBatch(
        partBatchId,
        batch.organizationId(),
        batch.objectKey(),
        batch.checksumSha256(),
        ContributionBatchStatus.PENDING_RECONCILIATION,
        new BatchDeclaration(
            batch.declared().planId(),
            batch.declared().payrollPeriod(),
            batch.declared().valueDate(),
            total,
            rows.size(),
            sourceTotals),
        Optional.empty(),
        batch.fileValidatedAt().or(() -> Optional.of(now)),
        Optional.empty(),
        Optional.empty(),
        batch.submittedAt(),
        now);
  }
}
