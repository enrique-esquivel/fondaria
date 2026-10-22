package dev.fondaria.allotmentjob.allotment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deduplicates on {@code (organizationId, batchId, participantInternalRef, source)}, mirroring
 * {@code JdbcAllotmentRepository}'s {@code ON CONFLICT ON CONSTRAINT allotment_pk DO NOTHING} — a
 * fake that let a rerun double-record would validate a weaker property than the real repository
 * actually guarantees.
 */
final class InMemoryAllotmentRepository implements AllotmentRepository {

  private final Map<List<Object>, AllotmentRecord> recorded = new LinkedHashMap<>();

  List<AllotmentRecord> recorded() {
    return List.copyOf(recorded.values());
  }

  @Override
  public void recordAll(List<AllotmentRecord> allotments) {
    for (AllotmentRecord allotment : allotments) {
      recorded.putIfAbsent(key(allotment), allotment);
    }
  }

  private static List<Object> key(AllotmentRecord allotment) {
    return List.of(
        allotment.organizationId(),
        allotment.batchId(),
        allotment.participantInternalRef(),
        allotment.source());
  }
}
