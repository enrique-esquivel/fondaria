package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link StagedRowRepository}, so what {@link ContributionStagingService} and {@link
 * BatchValidationRunner} decide — that a row is captured at most once, and that it only joins "the
 * staged set" once its batch's status says so — is provable without a database in the loop. The one
 * JDBC implementation proves the SQL, and the grants that make a row immutable, separately against
 * a real PostgreSQL in {@code JdbcStagedRowRepositoryIntegrationTest}.
 *
 * <p>Backed by the same {@link InMemoryContributionBatchRepository} the runner under test uses, so
 * {@link #find} performs the real join rather than approximating it.
 */
final class InMemoryStagedRowRepository implements StagedRowRepository {

  private final Map<String, StagedRow> rows = new LinkedHashMap<>();
  private final InMemoryContributionBatchRepository batches;

  InMemoryStagedRowRepository(InMemoryContributionBatchRepository batches) {
    this.batches = batches;
  }

  @Override
  public int insertAllIfAbsent(List<StagedRow> batch) {
    int inserted = 0;
    for (StagedRow row : batch) {
      if (rows.putIfAbsent(
              key(row.organizationId(), row.batchId(), row.participantInternalRef()), row)
          == null) {
        inserted++;
      }
    }
    return inserted;
  }

  @Override
  public List<StagedRow> findByBatch(OrganizationId organizationId, UUID batchId) {
    return rows.values().stream()
        .filter(row -> row.organizationId().equals(organizationId) && row.batchId().equals(batchId))
        .sorted(Comparator.comparing(StagedRow::participantInternalRef))
        .toList();
  }

  @Override
  public List<StagedRow> find(
      OrganizationId organizationId, String payrollPeriod, ContributionBatchStatus status) {
    return rows.values().stream()
        .filter(row -> row.organizationId().equals(organizationId))
        .filter(row -> row.payrollPeriod().equals(payrollPeriod))
        .filter(row -> statusOf(row.batchId()) == status)
        .sorted(Comparator.comparing(StagedRow::participantInternalRef))
        .toList();
  }

  int size() {
    return rows.size();
  }

  private ContributionBatchStatus statusOf(UUID batchId) {
    ContributionBatch batch = batches.get(batchId);
    return batch == null ? null : batch.status();
  }

  private static String key(OrganizationId organizationId, UUID batchId, String internalRef) {
    return organizationId.value() + "|" + batchId + "|" + internalRef;
  }
}
