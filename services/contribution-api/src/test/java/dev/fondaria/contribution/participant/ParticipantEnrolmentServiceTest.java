package dev.fondaria.contribution.participant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.batch.ContributionFile;
import dev.fondaria.contribution.batch.ContributionSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * What auto-enrolment decides, without a database: who gets created, and that seeing the same
 * participant again — whether from a re-swept batch or a later one — never creates a second row or
 * rewrites the first.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ParticipantEnrolmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
  private static final String PLAN_ID = "PLAN-ACME-CAJA-1";

  private final OrganizationId organization = new OrganizationId(UUID.randomUUID());
  private final InMemoryParticipantRepository repository = new InMemoryParticipantRepository();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private ParticipantEnrolmentService service;

  @BeforeEach
  void setUp() {
    service = new ParticipantEnrolmentService(repository, Clock.fixed(NOW, ZoneOffset.UTC), meters);
  }

  @Test
  void the_enrolment_counter_counts_new_participants_only() {
    // Row 26k: replaces balance-api's 15 s headcount query; a re-swept batch must add nothing.
    ContributionFile first =
        fileOf(
            row("P-001", "ACME-EMP-0142", LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15)),
            row("P-002", "ACME-EMP-0891", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));
    ContributionFile later =
        fileOf(
            row("P-002", "ACME-EMP-0891", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)),
            row("P-003", "ACME-EMP-0900", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1)));

    service.enrol(organization, PLAN_ID, first);
    service.enrol(organization, PLAN_ID, first);
    service.enrol(organization, PLAN_ID, later);

    assertThat(meters.get("fondaria_participants_enrolled").counter().count()).isEqualTo(3.0);
  }

  @Test
  void a_row_failing_mid_file_still_counts_the_rows_already_committed() {
    // Row 26k review: each insert commits on its own, and a retry re-finds those rows (no new
    // insert), so a count taken only at the end would lose them for good.
    SimpleMeterRegistry failingMeters = new SimpleMeterRegistry();
    ParticipantRepository failsOnSecond =
        new ParticipantRepository() {
          private int calls;

          @Override
          public boolean insertIfAbsent(Participant participant) {
            if (++calls == 2) {
              throw new IllegalStateException("payroll ref already claimed");
            }
            return repository.insertIfAbsent(participant);
          }

          @Override
          public java.util.Optional<Participant> find(OrganizationId org, String internalRef) {
            return repository.find(org, internalRef);
          }
        };
    ParticipantEnrolmentService failing =
        new ParticipantEnrolmentService(
            failsOnSecond, Clock.fixed(NOW, ZoneOffset.UTC), failingMeters);
    ContributionFile file =
        fileOf(
            row("P-001", "ACME-EMP-0142", LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15)),
            row("P-002", "ACME-EMP-0891", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> failing.enrol(organization, PLAN_ID, file))
        .isInstanceOf(IllegalStateException.class);

    assertThat(failingMeters.get("fondaria_participants_enrolled").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void every_row_becomes_a_participant_carrying_its_own_dates() {
    ContributionFile file =
        fileOf(
            row("P-001", "ACME-EMP-0142", LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15)),
            row("P-002", "ACME-EMP-0891", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1)));

    service.enrol(organization, PLAN_ID, file);

    assertThat(repository.size()).isEqualTo(2);
    assertThat(repository.find(organization, "P-001"))
        .hasValueSatisfying(
            participant -> {
              assertThat(participant.planId()).isEqualTo(PLAN_ID);
              assertThat(participant.payrollRef()).isEqualTo("ACME-EMP-0142");
              assertThat(participant.employmentStartDate()).isEqualTo(LocalDate.of(2019, 3, 1));
              assertThat(participant.enrolmentDate()).isEqualTo(LocalDate.of(2026, 1, 15));
              assertThat(participant.status()).isEqualTo(ParticipantStatus.ACTIVE);
              assertThat(participant.employmentStatus()).isEqualTo(EmploymentStatus.ACTIVE);
            });
  }

  @Test
  void re_sweeping_the_same_file_enrols_nobody_twice() {
    ContributionFile file =
        fileOf(row("P-001", "ACME-EMP-0142", LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15)));

    service.enrol(organization, PLAN_ID, file);
    service.enrol(organization, PLAN_ID, file);
    service.enrol(organization, PLAN_ID, file);

    assertThat(repository.size()).isEqualTo(1);
  }

  @Test
  void a_participant_seen_again_in_a_later_batch_is_matched_not_updated() {
    // "one it has seen is matched on participantInternalRef" (contribution-file-v2.md §3) —
    // a later file naming the same participant must not rewrite the seniority clock a
    // vesting schedule already runs off.
    service.enrol(
        organization,
        PLAN_ID,
        fileOf(row("P-001", "ACME-EMP-0142", LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15))));

    service.enrol(
        organization,
        PLAN_ID,
        fileOf(row("P-001", "ACME-EMP-0142", LocalDate.of(2020, 6, 1), LocalDate.of(2020, 6, 1))));

    assertThat(repository.size()).isEqualTo(1);
    assertThat(repository.find(organization, "P-001"))
        .hasValueSatisfying(
            participant ->
                assertThat(participant.employmentStartDate()).isEqualTo(LocalDate.of(2019, 3, 1)));
  }

  @Test
  void two_organizations_may_each_enrol_a_participant_with_the_same_payroll_ref() {
    OrganizationId other = new OrganizationId(UUID.randomUUID());
    ContributionFile file =
        fileOf(row("P-001", "E-001", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 1)));

    service.enrol(organization, PLAN_ID, file);
    service.enrol(other, PLAN_ID, file);

    assertThat(repository.find(organization, "P-001")).isPresent();
    assertThat(repository.find(other, "P-001")).isPresent();
    assertThat(repository.size()).isEqualTo(2);
  }

  private static ContributionFile fileOf(ContributionFile.ParticipantRow... rows) {
    return new ContributionFile(List.of(rows));
  }

  private static ContributionFile.ParticipantRow row(
      String internalRef, String payrollRef, LocalDate employmentStart, LocalDate enrolment) {
    return new ContributionFile.ParticipantRow(
        internalRef,
        payrollRef,
        employmentStart,
        enrolment,
        Map.of(ContributionSource.EMPLOYEE, 100_00L));
  }
}
