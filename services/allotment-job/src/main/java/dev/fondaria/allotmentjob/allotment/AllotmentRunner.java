package dev.fondaria.allotmentjob.allotment;

import dev.fondaria.allotmentjob.batch.StagedBatch;
import dev.fondaria.allotmentjob.batch.StagedBatchRepository;
import dev.fondaria.allotmentjob.batch.StagedRow;
import dev.fondaria.allotmentjob.config.AllotmentProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The allotment sweep: claims {@code STAGED} batches and fires {@link AllotmentService} against
 * each one, one batch per transaction.
 *
 * <p><b>One transaction per batch, not one for the whole sweep</b> — unlike contribution-api's
 * {@code BatchValidationRunner}, which claims its whole batch of work with a single lease-bearing
 * {@code UPDATE ... RETURNING} up front. That shape exists there to make an in-flight validation
 * (an object-store fetch and parse) recoverable by a different instance if the process dies
 * mid-file; here, {@code STAGED} itself is a perfectly good "not yet done" marker and {@link
 * StagedBatchRepository#lockForAllotment}'s {@code SELECT ... FOR UPDATE SKIP LOCKED} already gives
 * two instances the same "take whatever nobody else holds" property, held for exactly as long as
 * one batch's own ledger call — never one held lock spanning N batches' worth of network I/O.
 *
 * <p><b>Killing the job mid-run and rerunning produces no double allotment</b> (Task 21.2
 * acceptance criterion) two ways at once: a crash before commit rolls the whole transaction back,
 * so the batch is simply still {@code STAGED} and is retried whole; a crash <i>after</i> the ledger
 * chain posted but before commit — impossible to distinguish from a network timeout that actually
 * landed — is covered by {@link AllotmentService} checking whether the chain's first transfer
 * already exists before ever resending it (see that class's own javadoc for why it checks first
 * rather than resending and reading the result).
 */
@Component
public class AllotmentRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(AllotmentRunner.class);

  private final StagedBatchRepository batches;
  private final AllotmentService allotmentService;
  private final TransactionTemplate transactions;
  private final AllotmentProperties properties;
  private final Clock clock;

  public AllotmentRunner(
      StagedBatchRepository batches,
      AllotmentService allotmentService,
      TransactionTemplate transactions,
      AllotmentProperties properties,
      Clock clock) {
    this.batches = Objects.requireNonNull(batches, "batches must not be null");
    this.allotmentService =
        Objects.requireNonNull(allotmentService, "allotmentService must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Scheduled(
      fixedDelayString = "${fondaria.allotment.poll-interval:5s}",
      initialDelayString = "${fondaria.allotment.poll-interval:5s}")
  public int sweep() {
    List<UUID> candidates = batches.stagedBatchIds(properties.batchSize());
    int allotted = 0;
    for (UUID batchId : candidates) {
      if (allotOne(batchId)) {
        allotted++;
      }
    }
    return allotted;
  }

  private boolean allotOne(UUID batchId) {
    try {
      return Boolean.TRUE.equals(transactions.execute(status -> tryAllot(batchId, status)));
    } catch (RuntimeException e) {
      // A ledger rejection, or any other failure this method could not itself classify as "not
      // ready yet". The batch stays STAGED — an operator's problem, not a retry loop's, per
      // AllotmentService's own javadoc: every input the ledger enforces was engineered to hold
      // by construction, so a rejection means something upstream is wrong.
      LOGGER.error("Allotment failed for batch {}: {}", batchId, e.getMessage());
      return false;
    }
  }

  private boolean tryAllot(UUID batchId, TransactionStatus status) {
    Optional<StagedBatch> locked = batches.lockForAllotment(batchId);
    if (locked.isEmpty()) {
      // No longer STAGED, or another instance already holds its lock — either way, nothing
      // for this sweep to do.
      return false;
    }
    StagedBatch batch = locked.get();
    List<StagedRow> rows = batches.rowsFor(batch.organizationId(), batchId);

    Optional<LocalDate> dealingDate = allotmentService.allot(batch, rows);
    if (dealingDate.isEmpty()) {
      // Not ready: no resolvable plan version, no registered fund, or no NAV struck yet for
      // the dealing date. Roll back — nothing was written — and leave the batch STAGED for a
      // later sweep, once valuation-api has struck the price it is waiting on.
      status.setRollbackOnly();
      return false;
    }

    boolean marked = batches.markAllotted(batchId, clock.instant());
    if (!marked) {
      // The lock from lockForAllotment covers this whole transaction, so this should be
      // unreachable — guarded anyway, because silently reporting success without the status
      // write actually landing would be worse than failing loudly.
      throw new IllegalStateException(
          "Batch " + batchId + " was allotted but its STAGED -> ALLOTTED guard did not hold");
    }
    return true;
  }
}
