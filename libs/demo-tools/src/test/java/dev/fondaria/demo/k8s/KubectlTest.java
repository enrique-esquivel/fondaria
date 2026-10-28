package dev.fondaria.demo.k8s;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KubectlTest {

  @Test
  void aStatementKeysEqualsSignsAreEncodedSoCurlSignsWhatTheObjectStoreCanonicalizes() {
    assertThat(
            Kubectl.sigV4Path(
                "statement-objects/statements/period=2026-10/participant=5d6370b9-bb93.json"))
        .isEqualTo(
            "statement-objects/statements/period%3D2026-10/participant%3D5d6370b9-bb93.json");
  }

  @Test
  void unreservedCharactersAndSlashesAreLeftAsTheyAre() {
    assertThat(Kubectl.sigV4Path("eod-snapshots/dt_2026.10~a/A-z0-9"))
        .isEqualTo("eod-snapshots/dt_2026.10~a/A-z0-9");
  }

  @Test
  void everyOtherByteIsPercentEncodedAsUtf8() {
    assertThat(Kubectl.sigV4Path("b/a b+c:ñ")).isEqualTo("b/a%20b%2Bc%3A%C3%B1");
  }
}
