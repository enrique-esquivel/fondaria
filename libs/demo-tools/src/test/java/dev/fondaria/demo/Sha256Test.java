package dev.fondaria.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Sha256Test {

  @Test
  void matchesTheKnownDigestOfAnEmptyString() {
    // The canonical SHA-256 of the empty string, reused everywhere as a sanity check.
    assertThat(Sha256.hex(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void isDeterministicAndLowercaseHex() {
    String digest = Sha256.hex("contribution-file-v2");
    assertThat(digest).hasSize(64).matches("^[a-f0-9]{64}$");
    assertThat(Sha256.hex("contribution-file-v2")).isEqualTo(digest);
  }

  @Test
  void differentContentDigestsDiffer() {
    assertThat(Sha256.hex("a")).isNotEqualTo(Sha256.hex("b"));
  }
}
