package dev.fondaria.contribution.organization.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structural guard, not a behavioural one. "The tax identifier appears in no log" is asserted
 * against real log output elsewhere; this asserts the shape that makes it hard to get wrong in the
 * first place — nothing the API <i>returns</i> has a field to put one in.
 *
 * <p>It would otherwise be a one-line change, made in good faith by someone adding a field to a
 * response, to publish an employer's {@code RFC} to every client that can read an organization.
 */
class OrganizationApiNoTaxIdentifierLeakTest {

  /** The scheme is not the identifier: it says how the employer is keyed, never what the key is. */
  private static final List<String> ALLOWED = List.of("taxIdScheme");

  @Test
  @DisplayName("no response type carries a tax identifier")
  void responsesCarryNoTaxIdentifier() {
    assertNoTaxIdentifierComponent(OrganizationResponse.class);
    assertNoTaxIdentifierComponent(OrganizationResponse.Revision.class);
  }

  @Test
  @DisplayName("exactly one type accepts a tax identifier, and it is a request body")
  void onlyTheRegistrationRequestAcceptsOne() {
    assertThat(componentNames(RegisterOrganizationRequest.class)).contains("taxId");
    assertThat(componentNames(UpdateOrganizationRequest.class)).doesNotContain("taxId");
  }

  private static void assertNoTaxIdentifierComponent(Class<?> type) {
    assertThat(componentNames(type))
        .as("%s must not expose a tax identifier", type.getSimpleName())
        .filteredOn(name -> !ALLOWED.contains(name))
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("taxid"))
        .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("rfc"));
  }

  private static List<String> componentNames(Class<?> type) {
    RecordComponent[] components = type.getRecordComponents();
    assertThat(components).as("%s must be a record", type.getSimpleName()).isNotNull();
    return Arrays.stream(components).map(RecordComponent::getName).toList();
  }
}
