package dev.fondaria.contribution.participant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The AES-256-GCM envelope primitive behind Task 9.5, with no database or Spring context. */
class IdentityCipherTest {

  private final IdentityCipher cipher = new IdentityCipher();

  @Test
  @DisplayName("a sealed value opens back to the same plaintext under the same key and AAD")
  void sealThenOpenRoundTrips() {
    byte[] key = cipher.generateDataKey();
    byte[] plaintext = "XAXX010101000".getBytes(StandardCharsets.UTF_8);

    byte[] sealed = cipher.seal(key, plaintext, "org-1:ref-1:rfc");
    byte[] opened = cipher.open(key, sealed, "org-1:ref-1:rfc");

    assertThat(opened).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("two data keys are never equal, and two seals of the same plaintext never match")
  void keysAndCiphertextsAreRandomized() {
    byte[] keyA = cipher.generateDataKey();
    byte[] keyB = cipher.generateDataKey();
    assertThat(keyA).isNotEqualTo(keyB);

    byte[] plaintext = "same-value".getBytes(StandardCharsets.UTF_8);
    byte[] sealedOnce = cipher.seal(keyA, plaintext, "aad");
    byte[] sealedTwice = cipher.seal(keyA, plaintext, "aad");
    assertThat(sealedOnce).isNotEqualTo(sealedTwice);
  }

  @Test
  @DisplayName("opening under the wrong key fails rather than returning garbage")
  void wrongKeyFailsToOpen() {
    byte[] key = cipher.generateDataKey();
    byte[] wrongKey = cipher.generateDataKey();
    byte[] sealed = cipher.seal(key, "secret".getBytes(StandardCharsets.UTF_8), "aad");

    assertThatThrownBy(() -> cipher.open(wrongKey, sealed, "aad"))
        .isInstanceOf(IdentityDecryptionException.class);
  }

  @Test
  @DisplayName("a mismatched AAD fails to open, even under the correct key")
  void mismatchedAadFailsToOpen() {
    byte[] key = cipher.generateDataKey();
    byte[] sealed = cipher.seal(key, "secret".getBytes(StandardCharsets.UTF_8), "org-1:ref-1:rfc");

    assertThatThrownBy(() -> cipher.open(key, sealed, "org-1:ref-1:curp"))
        .isInstanceOf(IdentityDecryptionException.class);
  }

  @Test
  @DisplayName("a single flipped ciphertext byte is detected rather than silently decrypting")
  void tamperedCiphertextFailsToOpen() {
    byte[] key = cipher.generateDataKey();
    byte[] sealed = cipher.seal(key, "secret".getBytes(StandardCharsets.UTF_8), "aad");
    byte[] tampered = Arrays.copyOf(sealed, sealed.length);
    tampered[tampered.length - 1] ^= 0x01;

    assertThatThrownBy(() -> cipher.open(key, tampered, "aad"))
        .isInstanceOf(IdentityDecryptionException.class);
  }

  @Test
  @DisplayName("destroying the only reference to a data key makes its ciphertext unrecoverable")
  void destroyedKeyMakesCiphertextUnrecoverable() {
    // Simulates crypto-shredding at the primitive level: once the data key bytes are gone (here,
    // simply never retained), nothing -- not even this same class -- can open the ciphertext again.
    byte[] key = cipher.generateDataKey();
    byte[] sealed = cipher.seal(key, "secret".getBytes(StandardCharsets.UTF_8), "aad");
    Arrays.fill(key, (byte) 0);

    assertThatThrownBy(() -> cipher.open(key, sealed, "aad"))
        .isInstanceOf(IdentityDecryptionException.class);
  }
}
