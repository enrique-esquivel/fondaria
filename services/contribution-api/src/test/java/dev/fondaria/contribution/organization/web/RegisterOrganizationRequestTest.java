package dev.fondaria.contribution.organization.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The boundary where a tax identifier enters the system — and the only one. */
class RegisterOrganizationRequestTest {

  @Test
  @DisplayName("a punctuated, lower-case RFC canonicalizes to the same identifier")
  void canonicalizes() {
    RegisterOrganizationCommand command =
        new RegisterOrganizationRequest("MX_RFC", " abc-010101-xy1 ", "Acme", null, null)
            .toCommand();

    assertThat(command.taxIdentifier().value()).isEqualTo("ABC010101XY1");
    assertThat(command.taxIdentifier().scheme()).isEqualTo(TaxIdScheme.MX_RFC);
    assertThat(command.contractReference()).isEmpty();
    assertThat(command.feeBracket()).isEmpty();
  }

  @Test
  @DisplayName("blank optional fields are absent, never an empty string")
  void blankOptionalsAreAbsent() {
    RegisterOrganizationCommand command =
        new RegisterOrganizationRequest("MX_RFC", "ABC010101XY1", "Acme", "  ", "").toCommand();

    assertThat(command.contractReference()).isEqualTo(Optional.empty());
    assertThat(command.feeBracket()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("an ill-formed RFC is rejected without echoing the value back")
  void rejectsIllFormedRfcWithoutEchoingIt() {
    String offending = "NOT-AN-RFC-AT-ALL";

    assertThatThrownBy(
            () ->
                new RegisterOrganizationRequest("MX_RFC", offending, "Acme", null, null)
                    .toCommand())
        .isInstanceOf(InvalidOrganizationRequestException.class)
        .hasMessageNotContaining("NOTANRFCATALL")
        .hasMessageNotContaining(offending);
  }

  @Test
  @DisplayName("an unsupported scheme names the supported ones")
  void rejectsUnsupportedScheme() {
    assertThatThrownBy(
            () ->
                new RegisterOrganizationRequest("US_EIN", "ABC010101XY1", "Acme", null, null)
                    .toCommand())
        .isInstanceOf(InvalidOrganizationRequestException.class)
        .hasMessageContaining("MX_RFC");

    assertThatThrownBy(
            () ->
                new RegisterOrganizationRequest(null, "ABC010101XY1", "Acme", null, null)
                    .toCommand())
        .isInstanceOf(InvalidOrganizationRequestException.class);
  }

  @Test
  @DisplayName("a blank legal name is rejected")
  void rejectsBlankLegalName() {
    assertThatThrownBy(
            () ->
                new RegisterOrganizationRequest("MX_RFC", "ABC010101XY1", "  ", null, null)
                    .toCommand())
        .isInstanceOf(InvalidOrganizationRequestException.class)
        .hasMessageContaining("legalName");
  }

  @Test
  @DisplayName("the request never prints its tax identifier, not even to a debugger")
  void toStringRedactsTheTaxIdentifier() {
    RegisterOrganizationRequest request =
        new RegisterOrganizationRequest("MX_RFC", "ABC010101XY1", "Acme", null, null);

    assertThat(request.toString()).doesNotContain("ABC010101XY1").contains("REDACTED");
  }
}
