package dev.fondaria.contribution.organization.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.contribution.organization.OrganizationChange;
import dev.fondaria.contribution.organization.OrganizationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateOrganizationRequestTest {

  @Test
  @DisplayName("absent and blank fields both mean 'leave as it is'")
  void absentAndBlankBothMeanUnchanged() {
    OrganizationChange change =
        new UpdateOrganizationRequest("Acme Servicios", null, "   ", null).toChange();

    assertThat(change.legalName()).contains("Acme Servicios");
    assertThat(change.contractReference()).isEmpty();
    assertThat(change.feeBracket()).isEmpty();
    assertThat(change.status()).isEmpty();
    assertThat(change.isEmpty()).isFalse();
  }

  @Test
  @DisplayName("a request carrying nothing changes nothing")
  void emptyRequestIsAnEmptyChange() {
    assertThat(new UpdateOrganizationRequest(null, null, null, null).toChange().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("status is parsed case-insensitively, and an unknown one is rejected")
  void parsesStatus() {
    assertThat(new UpdateOrganizationRequest(null, null, null, "suspended").toChange().status())
        .contains(OrganizationStatus.SUSPENDED);

    assertThatThrownBy(() -> new UpdateOrganizationRequest(null, null, null, "DORMANT").toChange())
        .isInstanceOf(InvalidOrganizationRequestException.class)
        .hasMessageContaining("ACTIVE");
  }
}
