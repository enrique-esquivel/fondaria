package dev.fondaria.allotmentjob.vesting;

import java.util.List;

/**
 * Read-only view over {@code workflow.allotment} (Task 21.2) grouped down to what the vesting sweep
 * needs: every (participant, fund) pair that has ever received an {@code EMPLOYER}-sourced lot
 * under a scheduled (non-immediate) vesting policy.
 *
 * <p>An immediate-vesting plan never posts a {@code vested = false} row in the first place ({@code
 * AllotmentService}, Task 21.2), so such a plan's participants never appear here at all — the query
 * itself is what discharges Task 21.4's "an immediate-vesting plan never writes to the unvested
 * account" acceptance criterion, rather than a check in the caller.
 */
public interface VestingCandidateRepository {

  /** At most {@code limit} candidates. No ordering is guaranteed or required. */
  List<VestingCandidate> pendingCandidates(int limit);
}
