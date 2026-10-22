package dev.fondaria.allotmentjob.allotment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deduplicates on {@code batchId}, mirroring {@code JdbcAllotmentResidualRepository}'s {@code ON
 * CONFLICT ON CONSTRAINT allotment_residual_pk DO NOTHING} — a fake that let a rerun double-record
 * would validate a weaker property than the real repository actually guarantees.
 */
final class InMemoryAllotmentResidualRepository implements AllotmentResidualRepository {

  private final Map<UUID, AllotmentResidual> recorded = new LinkedHashMap<>();

  AllotmentResidual recordedFor(UUID batchId) {
    AllotmentResidual residual = recorded.get(batchId);
    if (residual == null) {
      throw new AssertionError("No residual recorded for batch " + batchId);
    }
    return residual;
  }

  @Override
  public void record(AllotmentResidual residual) {
    recorded.putIfAbsent(residual.batchId(), residual);
  }
}
