package dev.fondaria.rules;

import dev.fondaria.common.OrganizationId;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Storage for {@code reference.plan_version}.
 *
 * <p>An interface for the same reason {@code ParticipantRepository} is one: {@link
 * MatchingRatioRule} and {@link VestingScheduleCalculator} are provable without a database in the
 * loop. The one JDBC implementation (in the owning service) proves the SQL — including the
 * append-only grant, per {@code reference}'s schema-level privileges (ADR-0011) — separately,
 * against a real PostgreSQL.
 */
public interface PlanVersionRepository {

  /**
   * The version in force for {@code (organizationId, planId)} on {@code asOfDate}: the highest
   * {@code (effectiveFrom, version)} not after that date.
   *
   * <p>Resolution never changes for a date already evaluated, no matter how many later versions are
   * published — Task 2b.2 acceptance criterion 2.
   */
  Optional<PlanVersion> resolveAsOf(
      OrganizationId organizationId, String planId, LocalDate asOfDate);

  /**
   * Appends a new plan version. Never updates or deletes an existing one — a correction is a new
   * row, alongside the one it corrects, which remains readable (Task 2b.2 acceptance criterion 3).
   */
  PlanVersion publish(PlanVersion candidate);
}
