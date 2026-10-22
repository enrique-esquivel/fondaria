package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantRefTest {

  private static final UUID NAMESPACE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  private static final UUID OTHER_NAMESPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static OrganizationId org(String uuid) {
    return new OrganizationId(UUID.fromString(uuid));
  }

  @Test
  @DisplayName("NONE is zero — TigerBeetle's unset user_data_64")
  void noneIsZero() {
    assertThat(TenantRef.NONE.value()).isZero();
    assertThat(TenantRef.NONE.isNone()).isTrue();
    assertThat(new TenantRef(1L).isNone()).isFalse();
  }

  @Test
  @DisplayName(
      "the same organization and namespace always derive the same ref — no registry needed")
  void derivationIsDeterministic() {
    OrganizationId acme = org("11111111-1111-1111-1111-111111111111");
    assertThat(TenantRef.derive(NAMESPACE, acme)).isEqualTo(TenantRef.derive(NAMESPACE, acme));
  }

  @Test
  @DisplayName("two organizations do not share a ref")
  void differentOrganizationsDeriveDifferentRefs() {
    assertThat(TenantRef.derive(NAMESPACE, org("11111111-1111-1111-1111-111111111111")))
        .isNotEqualTo(TenantRef.derive(NAMESPACE, org("22222222-2222-2222-2222-222222222222")));
  }

  @Test
  @DisplayName("a different ledger namespace derives a different ref")
  void namespaceSeparatesDerivations() {
    OrganizationId acme = org("11111111-1111-1111-1111-111111111111");
    assertThat(TenantRef.derive(NAMESPACE, acme))
        .isNotEqualTo(TenantRef.derive(OTHER_NAMESPACE, acme));
  }

  @Test
  @DisplayName("round-trips through the unsigned base-10 form carried on the CDC stream")
  void roundTripsThroughUnsignedDecimal() {
    TenantRef ref = TenantRef.derive(NAMESPACE, org("11111111-1111-1111-1111-111111111111"));
    assertThat(TenantRef.fromUnsignedDecimal(ref.asUnsignedDecimal())).isEqualTo(ref);
  }

  @Test
  @DisplayName("a high-bit-set ref renders and parses as unsigned, never as a negative long")
  void highBitSetIsUnsigned() {
    TenantRef ref = new TenantRef(-1L); // all 64 bits set
    assertThat(ref.asUnsignedDecimal()).isEqualTo("18446744073709551615");
    assertThat(TenantRef.fromUnsignedDecimal("18446744073709551615")).isEqualTo(ref);
  }

  @Test
  void rejectsNonNumericText() {
    assertThatThrownBy(() -> TenantRef.fromUnsignedDecimal("not-a-number"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
