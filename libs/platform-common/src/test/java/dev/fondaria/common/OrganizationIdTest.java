package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrganizationIdTest {

  private static final UUID NAMESPACE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  private static final UUID OTHER_NAMESPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static TaxIdentifier rfc(String value) {
    return TaxIdentifier.of(TaxIdScheme.MX_RFC, value);
  }

  @Test
  @DisplayName("registering the same employer twice derives the same id — no registry needed")
  void derivationIsIdempotent() {
    assertThat(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")))
        .isEqualTo(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")));
  }

  @Test
  @DisplayName("a differently-spelled RFC is the same organization, not a second one")
  void spellingVariantsDeriveOneOrganization() {
    assertThat(OrganizationId.derive(NAMESPACE, rfc("acm-010101-ab1")))
        .isEqualTo(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")));
  }

  @Test
  @DisplayName("two employers never share an id")
  void differentEmployersDeriveDifferentIds() {
    assertThat(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")))
        .isNotEqualTo(OrganizationId.derive(NAMESPACE, rfc("BCM010101AB1")));
  }

  @Test
  @DisplayName("a different namespace derives a different id")
  void namespaceSeparatesDerivations() {
    assertThat(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")))
        .isNotEqualTo(OrganizationId.derive(OTHER_NAMESPACE, rfc("ACM010101AB1")));
  }

  @Test
  @DisplayName("the id round-trips through its string form, which is what travels in URLs")
  void roundTripsThroughString() {
    OrganizationId id = OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1"));
    assertThat(OrganizationId.parse(id.toString())).isEqualTo(id);
    assertThat(id.scope()).isEqualTo(id.value().toString());
  }

  @Test
  @DisplayName("the id never carries the tax identifier it was derived from")
  void doesNotLeakTheTaxIdentifier() {
    assertThat(OrganizationId.derive(NAMESPACE, rfc("ACM010101AB1")).toString())
        .doesNotContain("ACM010101AB1");
  }

  @Test
  @DisplayName("a non-UUID organizationId is rejected without echoing the input")
  void rejectsMalformedId() {
    assertThatThrownBy(() -> OrganizationId.parse("../../etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("passwd");
  }
}
