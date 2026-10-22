package dev.fondaria.contribution.participant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.contribution.participant.InMemoryParticipantLookupRepository;
import dev.fondaria.contribution.participant.ParticipantLookup;
import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Task D.0's participant-lookup acceptance criteria. */
class ParticipantLookupControllerTest {

  @Test
  @DisplayName("a participant is found by internalRef")
  void foundByInternalRef() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    ParticipantId participantId = ParticipantId.of(UUID.randomUUID());
    InMemoryParticipantLookupRepository repository = new InMemoryParticipantLookupRepository();
    repository.add(organizationId, new ParticipantLookup(participantId, "P-001", "PAYROLL-42"));
    ParticipantLookupController controller = new ParticipantLookupController(repository);

    ParticipantLookupResponse response =
        controller.lookup(organizationId.value().toString(), "P-001", null);

    assertThat(response.participantId()).isEqualTo(participantId.toString());
    assertThat(response.internalRef()).isEqualTo("P-001");
    assertThat(response.payrollRef()).isEqualTo("PAYROLL-42");
  }

  @Test
  @DisplayName("a participant is found by payrollRef")
  void foundByPayrollRef() {
    OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
    ParticipantId participantId = ParticipantId.of(UUID.randomUUID());
    InMemoryParticipantLookupRepository repository = new InMemoryParticipantLookupRepository();
    repository.add(organizationId, new ParticipantLookup(participantId, "P-001", "PAYROLL-42"));
    ParticipantLookupController controller = new ParticipantLookupController(repository);

    ParticipantLookupResponse response =
        controller.lookup(organizationId.value().toString(), null, "PAYROLL-42");

    assertThat(response.participantId()).isEqualTo(participantId.toString());
  }

  @Test
  @DisplayName("neither filter supplied is rejected")
  void neitherFilterIsRejected() {
    ParticipantLookupController controller =
        new ParticipantLookupController(new InMemoryParticipantLookupRepository());

    assertThatThrownBy(() -> controller.lookup(UUID.randomUUID().toString(), null, null))
        .isInstanceOf(InvalidParticipantLookupRequestException.class);
  }

  @Test
  @DisplayName("both filters supplied is rejected")
  void bothFiltersIsRejected() {
    ParticipantLookupController controller =
        new ParticipantLookupController(new InMemoryParticipantLookupRepository());

    assertThatThrownBy(() -> controller.lookup(UUID.randomUUID().toString(), "P-001", "PAYROLL-42"))
        .isInstanceOf(InvalidParticipantLookupRequestException.class);
  }

  @Test
  @DisplayName("an unknown reference is a named not-found, not an empty body")
  void unknownReferenceIsNotFound() {
    ParticipantLookupController controller =
        new ParticipantLookupController(new InMemoryParticipantLookupRepository());

    assertThatThrownBy(() -> controller.lookup(UUID.randomUUID().toString(), "P-404", null))
        .isInstanceOf(ParticipantNotFoundException.class);
  }
}
