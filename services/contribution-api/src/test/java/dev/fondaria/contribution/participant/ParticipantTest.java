package dev.fondaria.contribution.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ParticipantTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());

  @Test
  void the_two_employment_dates_are_stored_distinctly_and_independently_readable() {
    LocalDate employmentStart = LocalDate.of(2019, 3, 1);
    LocalDate enrolment = LocalDate.of(2026, 1, 15);

    Participant participant = participant(employmentStart, enrolment);

    // The whole thing 4b owes step 5: neither date is derived from the other, and both
    // survive being carried through the record unchanged.
    assertThat(participant.employmentStartDate()).isEqualTo(employmentStart);
    assertThat(participant.enrolmentDate()).isEqualTo(enrolment);
    assertThat(participant.employmentStartDate()).isNotEqualTo(participant.enrolmentDate());
  }

  @Test
  void an_enrolment_date_before_the_employment_start_date_is_refused() {
    assertThatThrownBy(() -> participant(LocalDate.of(2026, 1, 15), LocalDate.of(2019, 3, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toString_names_no_participant_field() {
    // docs/ENGINEERING-RULES.md §7: an internal ref, a payroll ref and the employment dates all
    // identify a
    // person, the same reasoning ContributionFile.ParticipantRow already applies.
    Participant participant = participant(LocalDate.of(2019, 3, 1), LocalDate.of(2026, 1, 15));

    String rendered = participant.toString();

    assertThat(rendered)
        .doesNotContain(participant.internalRef())
        .doesNotContain(participant.payrollRef())
        .doesNotContain("2019-03-01")
        .doesNotContain("2026-01-15");
  }

  private static Participant participant(LocalDate employmentStart, LocalDate enrolment) {
    return new Participant(
        ORGANIZATION,
        "P-" + UUID.randomUUID(),
        "PLAN-ACME-CAJA-1",
        "ACME-EMP-0142",
        ParticipantStatus.ACTIVE,
        EmploymentStatus.ACTIVE,
        employmentStart,
        enrolment,
        Optional.empty(),
        NOW,
        NOW);
  }
}
