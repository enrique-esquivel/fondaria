package dev.fondaria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The point of this type is that an organization's identity cannot drift: two spellings of one
 * employer's RFC must be one organization, because the ledger account holding that employer's money
 * is derived from it. A canonicalization gap here splits one employer's balance across two accounts
 * with nothing to detect it.
 */
class TaxIdentifierTest {

  /** A persona moral RFC: 3 letters, YYMMDD, 3-character homoclave. */
  private static final String ACME_MORAL = "ACM010101AB1";

  @Test
  @DisplayName("a persona moral RFC (12 characters) is accepted")
  void acceptsPersonaMoral() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, ACME_MORAL).value()).isEqualTo(ACME_MORAL);
  }

  @Test
  @DisplayName("a persona física RFC (13 characters) is accepted — an employer may be one")
  void acceptsPersonaFisica() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, "ACME010101AB1").value())
        .isEqualTo("ACME010101AB1");
  }

  @ParameterizedTest(name = "{0} canonicalizes to the same organization")
  @ValueSource(strings = {"acm010101ab1", "ACM-010101-AB1", "acm 010101 ab1", " ACM010101AB1 "})
  @DisplayName("spelling variants of one RFC are one organization, not several")
  void canonicalizesToOneIdentity(String variant) {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, variant))
        .isEqualTo(TaxIdentifier.of(TaxIdScheme.MX_RFC, ACME_MORAL));
  }

  @Test
  @DisplayName("Ñ and & are legal in the name portion")
  void acceptsSpanishNameCharacters() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, "OÑ&010101AB1").value())
        .isEqualTo("OÑ&010101AB1");
  }

  @ParameterizedTest(name = "accepts the generic RFC {0}")
  @ValueSource(strings = {"XAXX010101000", "XEXX010101000"})
  @DisplayName("the official generic RFCs are accepted — a foreign employer has no other one")
  void acceptsGenericRfcs(String generic) {
    // XAXX010101000 (no RFC) and XEXX010101000 (foreign resident) are officially assigned and
    // will appear on real files. They are also precisely the values that fail the check-digit
    // algorithm, which is why enforcing it without SAT reference data would reject them.
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, generic).value()).isEqualTo(generic);
  }

  @ParameterizedTest(name = "rejects {0}")
  @ValueSource(
      strings = {
        "ACM010101AB", // 11 characters — too short
        "ACME0101011AB1", // 14 characters — too long
        "AC1010101AB1", // digit inside the name portion
        "ACM011301AB1", // month 13
        "ACM010132AB1", // day 32
        "ACM010230AB1", // 30 February
        "ACM010100AB1", // day 00
        "ACM0101O1AB1" // letter inside the date portion
      })
  @DisplayName("a malformed RFC cannot be constructed")
  void rejectsMalformed(String malformed) {
    assertThatThrownBy(() -> TaxIdentifier.of(TaxIdScheme.MX_RFC, malformed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("29 February is accepted — the century is unknown, so the leap year cannot be")
  void acceptsFebruary29() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, "ACM010229AB1").value())
        .isEqualTo("ACM010229AB1");
  }

  @Test
  @DisplayName("the derivation key carries the scheme, so countries cannot collide")
  void derivationKeyIsSchemeQualified() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, ACME_MORAL).derivationKey())
        .isEqualTo("MX:RFC:" + ACME_MORAL);
  }

  @Test
  @DisplayName("toString redacts the value — a tax identifier must never reach a log")
  void toStringRedactsTheValue() {
    assertThat(TaxIdentifier.of(TaxIdScheme.MX_RFC, ACME_MORAL).toString())
        .doesNotContain(ACME_MORAL)
        .contains("REDACTED");
  }

  @Test
  @DisplayName("a rejection message never quotes the offending value")
  void rejectionMessageOmitsTheValue() {
    String malformed = "ACM019901AB1";
    assertThatThrownBy(() -> TaxIdentifier.of(TaxIdScheme.MX_RFC, malformed))
        .hasMessageNotContaining(malformed);
  }
}
