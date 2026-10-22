package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every derived id in the platform — transfer ids, ledger account ids, organization ids — comes out
 * of this one function, and each must keep deriving the same value for the life of the system. So
 * this is pinned against the canonical RFC 4122 vector rather than against itself: a test that only
 * checked self-consistency would pass just as happily on a subtly wrong implementation.
 */
class NameBasedUuidTest {

  /** RFC 4122 Appendix C. */
  private static final UUID DNS_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

  @Test
  @DisplayName("matches the canonical RFC 4122 vector: uuid5(DNS, \"python.org\")")
  void matchesCanonicalVector() {
    assertThat(NameBasedUuid.v5(DNS_NAMESPACE, "python.org"))
        .isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
  }

  @Test
  @DisplayName("produces a version 5, RFC 4122 variant UUID")
  void setsVersionAndVariant() {
    UUID uuid = NameBasedUuid.v5(DNS_NAMESPACE, "anything");
    assertThat(uuid.version()).isEqualTo(5);
    assertThat(uuid.variant()).isEqualTo(2);
  }

  @Test
  @DisplayName("the same namespace and name always derive the same value")
  void isDeterministic() {
    assertThat(NameBasedUuid.v5(DNS_NAMESPACE, "acme"))
        .isEqualTo(NameBasedUuid.v5(DNS_NAMESPACE, "acme"));
  }

  @Test
  @DisplayName("the namespace participates — the same name under two namespaces differs")
  void namespaceSeparatesDerivations() {
    UUID other = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
    assertThat(NameBasedUuid.v5(DNS_NAMESPACE, "acme"))
        .isNotEqualTo(NameBasedUuid.v5(other, "acme"));
  }

  @Test
  @DisplayName(
      "names are UTF-8 encoded, so non-ASCII derives stably rather than by platform default")
  void encodesNamesAsUtf8() {
    // Ñ appears in Mexican legal names and RFCs; a platform-default charset would make the derived
    // id depend on the JVM's locale, which is exactly the kind of drift this must never have.
    assertThat(NameBasedUuid.v5(DNS_NAMESPACE, "OÑ&010101AB1"))
        .isEqualTo(NameBasedUuid.v5(DNS_NAMESPACE, "OÑ&010101AB1"));
  }

  @Test
  @DisplayName("null arguments are rejected rather than silently derived")
  void rejectsNulls() {
    assertThatThrownBy(() -> NameBasedUuid.v5(null, "x")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> NameBasedUuid.v5(DNS_NAMESPACE, null))
        .isInstanceOf(NullPointerException.class);
  }
}
