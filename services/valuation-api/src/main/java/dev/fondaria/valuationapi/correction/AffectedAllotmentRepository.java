package dev.fondaria.valuationapi.correction;

import java.util.List;

/**
 * Read-only access to {@code workflow.allotment} (allotment-job's own table, Task 21.2), the same
 * cross-service pattern {@code allotment-job}'s own {@code ParticipantDirectory} uses against
 * {@code master.participant}: this service never writes an allotment, only asks which ones named a
 * NAV a correction is about to supersede.
 */
public interface AffectedAllotmentRepository {

  /**
   * Every allotment recorded against {@code navVersionId} — the price a correction is superseding.
   * Empty when nothing was ever struck at that price, which is not an error: a corrected NAV that
   * priced no allotment yet is the ordinary case for same-day corrections.
   */
  List<AffectedAllotment> findByNavVersionId(long navVersionId);
}
