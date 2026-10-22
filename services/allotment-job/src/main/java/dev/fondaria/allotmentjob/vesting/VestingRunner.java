package dev.fondaria.allotmentjob.vesting;

import dev.fondaria.allotmentjob.config.VestingProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The vesting sweep: reads every (participant, fund) candidate with employer units still
 * outstanding in {@code PARTICIPANT_HOLDINGS_UNVESTED} ({@link VestingCandidateRepository}) and
 * fires {@link VestingService} against each one.
 *
 * <p><b>No local transaction, unlike {@code AllotmentRunner}.</b> {@link VestingService#vest}
 * writes nothing to PostgreSQL — no batch status to flip, no audit row to insert in the same commit
 * — so there is no local write here that a Postgres transaction would need to make atomic with the
 * ledger call. Its own idempotency comes entirely from recomputing the target vested amount fresh
 * against the ledger's current balance every time (see that class's own javadoc), not from a
 * transaction boundary.
 */
@Component
public class VestingRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(VestingRunner.class);

  private final VestingCandidateRepository candidates;
  private final VestingService vestingService;
  private final VestingProperties properties;

  public VestingRunner(
      VestingCandidateRepository candidates,
      VestingService vestingService,
      VestingProperties properties) {
    this.candidates = Objects.requireNonNull(candidates, "candidates must not be null");
    this.vestingService = Objects.requireNonNull(vestingService, "vestingService must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  @Scheduled(
      fixedDelayString = "${fondaria.vesting.poll-interval:1m}",
      initialDelayString = "${fondaria.vesting.poll-interval:1m}")
  public int sweep() {
    List<VestingCandidate> pending = candidates.pendingCandidates(properties.batchSize());
    int vested = 0;
    for (VestingCandidate candidate : pending) {
      if (vestOne(candidate)) {
        vested++;
      }
    }
    return vested;
  }

  private boolean vestOne(VestingCandidate candidate) {
    try {
      return vestingService.vest(candidate).map(units -> !units.isZero()).orElse(false);
    } catch (RuntimeException e) {
      // A ledger rejection, or any other failure vest() could not itself classify as "not
      // ready yet". Left as a candidate for the next sweep -- an operator's problem, the same
      // reasoning AllotmentRunner's own catch block gives.
      LOGGER.error("Vesting failed for fund {}: {}", candidate.fundId(), e.getMessage());
      return false;
    }
  }
}
