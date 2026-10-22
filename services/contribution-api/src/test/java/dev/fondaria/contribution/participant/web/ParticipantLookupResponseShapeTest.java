package dev.fondaria.contribution.participant.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structural guard, not a behavioural one — mirrors {@code
 * OrganizationApiNoTaxIdentifierLeakTest}. The lookup's whole job is to resolve a reference to
 * {@code participantId}; nothing else about the participant may ride along in the same response
 * (AGENTS.md §7: no name, no employment dates).
 */
class ParticipantLookupResponseShapeTest {

  private static final Set<String> ALLOWED = Set.of("participantId", "internalRef", "payrollRef");

  @Test
  @DisplayName("the lookup response carries exactly participantId, internalRef and payrollRef")
  void responseCarriesOnlyTheThreeFields() {
    RecordComponent[] components = ParticipantLookupResponse.class.getRecordComponents();
    assertThat(components).isNotNull();
    List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();

    assertThat(names).containsExactlyInAnyOrderElementsOf(ALLOWED);
  }
}
