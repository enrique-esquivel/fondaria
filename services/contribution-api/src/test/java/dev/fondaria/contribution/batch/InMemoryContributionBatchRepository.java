package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ContributionBatchRepository}, so the decisions {@link BatchValidationRunner}
 * makes — which check fails first, what a store outage does, when a batch becomes pending rather
 * than rejected — are provable without a database in the loop.
 *
 * <p>It mirrors the real repository's contract rather than simplifying it: {@code submit} really is
 * conditional on the content address, {@code claimForValidation} really does honour the staleness
 * cutoff, and {@code resolve} really does refuse a batch that is no longer {@code VALIDATING}.
 * Those are the behaviours the runner depends on, so a double that ignored them would prove
 * nothing. It is a {@link java.util.Map}, not a SQL engine: the SQL is proved separately, against a
 * real PostgreSQL, in {@code JdbcContributionBatchRepositoryIntegrationTest}.
 */
final class InMemoryContributionBatchRepository implements ContributionBatchRepository {

  private final Map<UUID, ContributionBatch> batches = new LinkedHashMap<>();

  /**
   * The two part columns V24 adds, kept beside the batch rather than on it: {@link
   * ContributionBatch} does not carry them, because nothing above the repository reads them
   * directly — a part is excluded from a sweep, or counted into a progress summary, by the
   * repository itself.
   */
  private final Map<UUID, UUID> parents = new LinkedHashMap<>();

  private final Map<UUID, Integer> partNumbers = new LinkedHashMap<>();

  @Override
  public ContributionBatch submit(ContributionBatch batch) {
    for (ContributionBatch existing : batches.values()) {
      if (existing.organizationId().equals(batch.organizationId())
          && existing.objectKey().equals(batch.objectKey())
          && existing.checksumSha256().equals(batch.checksumSha256())) {
        return existing;
      }
    }
    batches.put(batch.batchId(), batch);
    return batch;
  }

  @Override
  public Optional<ContributionBatch> find(OrganizationId organizationId, UUID batchId) {
    return Optional.ofNullable(batches.get(batchId))
        .filter(batch -> batch.organizationId().equals(organizationId));
  }

  @Override
  public boolean insertPartIfAbsent(ContributionBatch part, UUID fileBatchId, int partNumber) {
    if (batches.containsKey(part.batchId())) {
      return false;
    }
    batches.put(part.batchId(), part);
    parents.put(part.batchId(), fileBatchId);
    partNumbers.put(part.batchId(), partNumber);
    return true;
  }

  @Override
  public int stageParts(UUID fileBatchId, Instant stagedAt, Instant statusUpdatedAt) {
    int moved = 0;
    for (UUID partId : List.copyOf(partsOf(fileBatchId))) {
      ContributionBatch part = batches.get(partId);
      if (part.status() != ContributionBatchStatus.PENDING_RECONCILIATION) {
        continue;
      }
      batches.put(
          partId,
          new ContributionBatch(
              part.batchId(),
              part.organizationId(),
              part.objectKey(),
              part.checksumSha256(),
              ContributionBatchStatus.STAGED,
              part.declared(),
              Optional.empty(),
              part.fileValidatedAt(),
              Optional.empty(),
              part.stagedAt().or(() -> Optional.of(stagedAt)),
              part.submittedAt(),
              statusUpdatedAt));
      moved++;
    }
    return moved;
  }

  @Override
  public Optional<BatchPartProgress> progressOf(OrganizationId organizationId, UUID batchId) {
    ContributionBatch asked = batches.get(batchId);
    if (asked == null || !asked.organizationId().equals(organizationId)) {
      return Optional.empty();
    }
    UUID fileBatchId = parents.getOrDefault(batchId, batchId);
    List<UUID> family = new ArrayList<>();
    family.add(fileBatchId);
    family.addAll(partsOf(fileBatchId));
    int allotted =
        (int)
            family.stream()
                .map(batches::get)
                .filter(batch -> batch.status() == ContributionBatchStatus.ALLOTTED)
                .count();
    return Optional.of(
        new BatchPartProgress(
            fileBatchId, partNumbers.getOrDefault(batchId, 1), family.size(), allotted));
  }

  private List<UUID> partsOf(UUID fileBatchId) {
    return parents.entrySet().stream()
        .filter(entry -> entry.getValue().equals(fileBatchId))
        .map(Map.Entry::getKey)
        .sorted(Comparator.comparing(id -> partNumbers.getOrDefault(id, 1)))
        .toList();
  }

  @Override
  public List<ContributionBatch> claimForValidation(Instant now, Instant staleBefore, int limit) {
    List<ContributionBatch> claimed = new ArrayList<>();
    List<ContributionBatch> candidates =
        batches.values().stream()
            // Files only (ADR-0017), the same restriction the real query carries.
            .filter(batch -> !parents.containsKey(batch.batchId()))
            .filter(batch -> isClaimable(batch, staleBefore))
            .sorted(Comparator.comparing(ContributionBatch::submittedAt))
            .limit(limit)
            .toList();
    for (ContributionBatch batch : candidates) {
      ContributionBatch working =
          new ContributionBatch(
              batch.batchId(),
              batch.organizationId(),
              batch.objectKey(),
              batch.checksumSha256(),
              ContributionBatchStatus.VALIDATING,
              batch.declared(),
              Optional.empty(),
              batch.fileValidatedAt(),
              Optional.of(now),
              Optional.empty(),
              batch.submittedAt(),
              now);
      batches.put(working.batchId(), working);
      claimed.add(working);
    }
    return claimed;
  }

  private boolean isClaimable(ContributionBatch batch, Instant staleBefore) {
    boolean baseClaimable =
        switch (batch.status()) {
          case RECEIVED, PENDING_RECONCILIATION -> true;
          case VALIDATING ->
              batch
                  .validationStartedAt()
                  .map(started -> started.isBefore(staleBefore))
                  .orElse(true);
          case VALIDATED, STAGED, ALLOTTED, REJECTED -> false;
        };
    if (!baseClaimable) {
      return false;
    }
    return partsOf(batch.batchId()).stream()
        .map(batches::get)
        .noneMatch(
            part ->
                part.status() == ContributionBatchStatus.STAGED
                    || part.status() == ContributionBatchStatus.ALLOTTED);
  }

  @Override
  public boolean markFileValidated(UUID batchId, Instant fileValidatedAt) {
    ContributionBatch stored = batches.get(batchId);
    if (stored == null || stored.status() != ContributionBatchStatus.VALIDATING) {
      return false;
    }
    batches.put(
        batchId,
        new ContributionBatch(
            stored.batchId(),
            stored.organizationId(),
            stored.objectKey(),
            stored.checksumSha256(),
            stored.status(),
            stored.declared(),
            stored.rejection(),
            Optional.of(fileValidatedAt),
            stored.validationStartedAt(),
            stored.stagedAt(),
            stored.submittedAt(),
            fileValidatedAt));
    return true;
  }

  @Override
  public boolean releaseValidation(UUID batchId, Instant now) {
    ContributionBatch stored = batches.get(batchId);
    if (stored == null || stored.status() != ContributionBatchStatus.VALIDATING) {
      return false;
    }
    ContributionBatchStatus nextStatus =
        stored.fileValidatedAt().isPresent()
            ? ContributionBatchStatus.PENDING_RECONCILIATION
            : ContributionBatchStatus.RECEIVED;
    batches.put(
        batchId,
        new ContributionBatch(
            stored.batchId(),
            stored.organizationId(),
            stored.objectKey(),
            stored.checksumSha256(),
            nextStatus,
            stored.declared(),
            stored.rejection(),
            stored.fileValidatedAt(),
            Optional.empty(),
            stored.stagedAt(),
            stored.submittedAt(),
            now));
    return true;
  }

  @Override
  public boolean resolve(ContributionBatch batch) {
    ContributionBatch stored = batches.get(batch.batchId());
    if (stored == null || stored.status() != ContributionBatchStatus.VALIDATING) {
      return false;
    }
    batches.put(batch.batchId(), batch);
    return true;
  }

  @Override
  public List<ContributionBatch> pendingReconciliation(OrganizationId organizationId) {
    return batches.values().stream()
        .filter(batch -> batch.organizationId().equals(organizationId))
        .filter(batch -> !parents.containsKey(batch.batchId()))
        .filter(batch -> batch.status() == ContributionBatchStatus.PENDING_RECONCILIATION)
        .sorted(Comparator.comparing(ContributionBatch::submittedAt))
        .toList();
  }

  /** Reads a batch back without the organization scoping, for assertions. */
  ContributionBatch get(UUID batchId) {
    return batches.get(batchId);
  }

  /** Simulates allotment-job marking a batch or part allotted in the ledger. */
  void markAllotted(UUID batchId, Instant allottedAt) {
    ContributionBatch stored = batches.get(batchId);
    if (stored != null) {
      batches.put(
          batchId,
          new ContributionBatch(
              stored.batchId(),
              stored.organizationId(),
              stored.objectKey(),
              stored.checksumSha256(),
              ContributionBatchStatus.ALLOTTED,
              stored.declared(),
              stored.rejection(),
              stored.fileValidatedAt(),
              Optional.empty(),
              stored.stagedAt(),
              stored.submittedAt(),
              allottedAt));
    }
  }
}
