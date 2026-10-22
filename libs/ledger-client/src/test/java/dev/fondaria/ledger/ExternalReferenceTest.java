package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tie-out works by recomputation: take a line off the bank statement, derive it, look the transfer
 * up. That only works if derivation is stable and collision-free, so both are pinned here.
 */
class ExternalReferenceTest {

  private static final UUID NAMESPACE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  private static final UUID OTHER_NAMESPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000003");

  @Test
  @DisplayName("the same bank reference always derives the same value")
  void derivationIsStable() {
    assertThat(ExternalReference.derive(NAMESPACE, "SPEI-20260818-0001234"))
        .isEqualTo(ExternalReference.derive(NAMESPACE, "SPEI-20260818-0001234"));
  }

  @Test
  @DisplayName("two bank references never derive the same value")
  void differentReferencesDeriveDifferentValues() {
    assertThat(ExternalReference.derive(NAMESPACE, "SPEI-20260818-0001234"))
        .isNotEqualTo(ExternalReference.derive(NAMESPACE, "SPEI-20260818-0001235"));
  }

  @Test
  @DisplayName("a different namespace derives a different value")
  void namespaceSeparatesDerivations() {
    assertThat(ExternalReference.derive(NAMESPACE, "SPEI-20260818-0001234"))
        .isNotEqualTo(ExternalReference.derive(OTHER_NAMESPACE, "SPEI-20260818-0001234"));
  }

  @Test
  @DisplayName("NONE is absent; a derived reference is present")
  void presenceReflectsWhetherAReferenceWasRecorded() {
    assertThat(ExternalReference.NONE.isPresent()).isFalse();
    assertThat(ExternalReference.derive(NAMESPACE, "SPEI-1").isPresent()).isTrue();
  }

  @Test
  @DisplayName("NONE encodes as zero, which is the ledger's own 'unset'")
  void noneIsZero() {
    assertThat(ExternalReference.NONE.mostSignificantBits()).isZero();
    assertThat(ExternalReference.NONE.leastSignificantBits()).isZero();
  }

  @Test
  @DisplayName("a blank external id is rejected rather than derived into a real-looking reference")
  void blankExternalIdIsRejected() {
    assertThatThrownBy(() -> ExternalReference.derive(NAMESPACE, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("externalId");
  }
}
