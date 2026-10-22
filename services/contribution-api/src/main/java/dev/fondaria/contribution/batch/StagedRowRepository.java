package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import java.util.List;
import java.util.UUID;

/**
 * Storage for {@code workflow.contribution_batch_row} — Task 3.6.
 *
 * <p>An interface for the same reason {@code ParticipantRepository} is one: what {@link
 * ContributionStagingService} decides should be provable without a database in the loop, and the
 * one JDBC implementation proves the SQL, and the grants that make a row immutable, separately
 * against a real PostgreSQL.
 */
public interface StagedRowRepository {

  /**
   * Records a row if it has not been recorded before, keyed on {@code (organizationId, batchId,
   * participantInternalRef)}.
   *
   * <p>Conditional for the same reason {@code ParticipantRepository#insertIfAbsent} is: {@code
   * BatchValidationRunner} may call this more than once for the same batch if a process dies
   * between capturing rows and persisting {@code fileValidatedAt}, and a re-swept batch must record
   * each row at most once.
   *
   * @return whether this call recorded the row
   */
  boolean insertIfAbsent(StagedRow row);

  /** Every row a batch has recorded, whatever the batch's current status. */
  List<StagedRow> findByBatch(OrganizationId organizationId, UUID batchId);

  /**
   * The staged set: rows whose batch belongs to {@code organizationId}, declared {@code
   * payrollPeriod}, and currently carries {@code status} — Task 3.6's "queryable by organization,
   * period and status".
   */
  List<StagedRow> find(
      OrganizationId organizationId, String payrollPeriod, ContributionBatchStatus status);
}
