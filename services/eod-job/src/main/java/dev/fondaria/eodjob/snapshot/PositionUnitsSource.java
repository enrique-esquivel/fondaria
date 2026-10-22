package dev.fondaria.eodjob.snapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What each holding account held at the close of a business date, from {@code
 * projection.participant_units}.
 *
 * <p>phase-07 Task 7.4 names this source explicitly: the snapshot is derived from {@code
 * participant_units × fund_nav}, <b>not</b> from the stream processor. The distinction matters —
 * reading the consumer's in-flight state would make the day's artefact depend on where the consumer
 * happened to be, and two runs would disagree for reasons nothing recorded.
 *
 * <p>Read-only against a CDC-fed schema (ADR-0011): {@code projection} is never written by a
 * service, and no implementation of this interface holds an {@code INSERT} or an {@code UPDATE}.
 */
public interface PositionUnitsSource {

  /**
   * Units held at or before {@code asOfDate}, keyed by account id in canonical unsigned decimal —
   * the form {@code AccountId.asUnsignedDecimal()} produces and {@code
   * projection.participant_units} stores.
   *
   * <p><b>An absent account holds nothing, and that is the truth rather than a gap.</b> The table
   * is sparse: a row exists only when a holding changed. An account with no row at or before the
   * date had not been issued into by then.
   *
   * <p><b>This is what makes the snapshot dense.</b> A participant who transacted once in March and
   * not since has no March-onward rows at all, and every subsequent business date reads that same
   * March row — so they appear, with the units they still hold, on every day's file. Density is a
   * property of asking the question per date, not of the projection storing a row per date.
   *
   * @param tenantId the organization's {@code TenantRef} as unsigned decimal — the partition key,
   *     tenant first (ADR-0005)
   * @param accountIds holding account ids, canonical unsigned decimal
   */
  Map<String, Long> unitsAsOf(String tenantId, List<String> accountIds, LocalDate asOfDate);
}
