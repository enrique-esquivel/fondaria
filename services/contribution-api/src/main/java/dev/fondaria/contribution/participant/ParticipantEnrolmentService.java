package dev.fondaria.contribution.participant;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.batch.ContributionFile;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Auto-enrolment: "the contribution file may enrol" (phase-02b "Scope decisions taken for v1").
 *
 * <p>Called from {@code BatchValidationRunner}'s path, after a file has passed every structural
 * check of {@code contribution-file-v2.md} §4 — a batch that fails one of those checks is rejected
 * whole and enrols nobody, the same "never a partial allotment" docs/ENGINEERING-RULES.md §1 states
 * for money.
 *
 * <p><b>Idempotent by construction, not by a marker.</b> Enrolling reduces to {@link
 * ParticipantRepository#insertIfAbsent}, keyed on {@code (organizationId, internalRef)}, so
 * re-sweeping a batch — or a lease surviving a crash and being retried — enrols each participant at
 * most once whether this method runs once or five times against the same rows.
 *
 * <p><b>The enrolment counter</b> ({@code fondaria_participants_enrolled_total}, BACKLOG row 26k)
 * is incremented by the rows {@code insertIfAbsent} actually created, so a re-swept batch adds
 * nothing. It replaces balance-api's 15 s {@code COUNT(DISTINCT …)} headcount query: a counter
 * answers "how many were enrolled in this window" ({@code increase()}), not "how many are enrolled"
 * -- it lives in this pod's memory and restarts at zero.
 */
@Component
public class ParticipantEnrolmentService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ParticipantEnrolmentService.class);

  /**
   * Participants per call to {@link ParticipantRepository#insertAllIfAbsent}, and so per commit: no
   * transaction is open here. One commit per participant waited ~4 ms each for cross-zone standby
   * acknowledgements on GKE, ~120 participants/s on an idle cluster (load run 2026-10-30);
   * enrolment runs for every row of every file, already-enrolled participants included.
   */
  static final int ROWS_PER_COMMIT = 1_000;

  private final ParticipantRepository repository;
  private final Clock clock;
  private final Counter enrolled;

  public ParticipantEnrolmentService(
      ParticipantRepository repository, Clock clock, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.clock = clock;
    this.enrolled =
        Counter.builder("fondaria_participants_enrolled")
            .description(
                "Participants enrolled by contribution files (new master.participant rows only)."
                    + " Per pod, restarts at zero: use increase(), never the raw value.")
            .register(meterRegistry);
  }

  /**
   * @param organizationId the tenant the file was submitted under
   * @param planId the plan the batch declared — {@code contribution-file-v2.md} §1.1: the file
   *     itself carries no plan
   * @param file a file that has already passed {@code ContributionFileParser} and {@code
   *     ContributionBatchValidator}
   */
  public void enrol(OrganizationId organizationId, String planId, ContributionFile file) {
    Instant now = clock.instant();
    int created = 0;
    List<ContributionFile.ParticipantRow> rows = file.rows();
    for (int from = 0; from < rows.size(); from += ROWS_PER_COMMIT) {
      List<Participant> chunk = new ArrayList<>(ROWS_PER_COMMIT);
      for (ContributionFile.ParticipantRow row :
          rows.subList(from, Math.min(rows.size(), from + ROWS_PER_COMMIT))) {
        chunk.add(
            new Participant(
                organizationId,
                row.participantInternalRef(),
                planId,
                row.participantPayrollRef(),
                ParticipantStatus.ACTIVE,
                EmploymentStatus.ACTIVE,
                row.employmentStartDate(),
                row.enrolmentDate(),
                java.util.Optional.empty(),
                now,
                now));
      }
      int inserted = repository.insertAllIfAbsent(chunk);
      // Per chunk, not once at the end: each chunk commits on its own, so a chunk failing
      // mid-file must not leave the ones before it uncounted forever (a retry re-finds them).
      enrolled.increment(inserted);
      created += inserted;
    }
    // Counts only. Neither an internal ref nor a payroll ref reaches a log
    // (docs/ENGINEERING-RULES.md §7) —
    // every field of a participant row identifies a person.
    LOGGER.info(
        "Contribution batch enrolment processed, organizationId={}, rowsSeen={},"
            + " participantsCreated={}",
        organizationId,
        file.rows().size(),
        created);
  }
}
