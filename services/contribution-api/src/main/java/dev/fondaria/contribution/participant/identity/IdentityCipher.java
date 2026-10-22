package dev.fondaria.contribution.participant.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM primitive behind Task 9.5's envelope encryption: generates 256-bit data keys and
 * seals/opens byte strings under a given key, with the random 12-byte IV prefixed to the returned
 * ciphertext so nothing else needs to be stored alongside it.
 *
 * <p>Used twice per identity field, at two different layers of the envelope — once to wrap a
 * participant's own data key under the master key, and once per field to encrypt it under that data
 * key — with the same primitive either time. Which key is a data key and which is the master key is
 * a caller concern; this class only ever sees bytes.
 *
 * <p>The additional authenticated data (AAD) callers pass binds a ciphertext to the row and field
 * it belongs to: a ciphertext moved to a different participant, or swapped between two columns of
 * the same width, fails to open rather than silently decrypting into the wrong field.
 */
final class IdentityCipher {

  static final int DATA_KEY_BYTES = 32;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int GCM_IV_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private final SecureRandom random = new SecureRandom();

  byte[] generateDataKey() {
    byte[] key = new byte[DATA_KEY_BYTES];
    random.nextBytes(key);
    return key;
  }

  /** Returns {@code iv (12 bytes) || ciphertext-with-tag}. */
  byte[] seal(byte[] key, byte[] plaintext, String aad) {
    byte[] iv = new byte[GCM_IV_BYTES];
    random.nextBytes(iv);
    byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, key, iv, aad, plaintext);
    byte[] sealed = new byte[iv.length + ciphertext.length];
    System.arraycopy(iv, 0, sealed, 0, iv.length);
    System.arraycopy(ciphertext, 0, sealed, iv.length, ciphertext.length);
    return sealed;
  }

  /** The inverse of {@link #seal}. Throws {@link IdentityDecryptionException} on any failure. */
  byte[] open(byte[] key, byte[] sealed, String aad) {
    if (sealed == null || sealed.length <= GCM_IV_BYTES) {
      throw new IdentityDecryptionException();
    }
    byte[] iv = Arrays.copyOfRange(sealed, 0, GCM_IV_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(sealed, GCM_IV_BYTES, sealed.length);
    return transform(Cipher.DECRYPT_MODE, key, iv, aad, ciphertext);
  }

  private byte[] transform(int mode, byte[] key, byte[] iv, String aad, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          mode, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(GCM_TAG_BITS, iv));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      // Never wraps the underlying message: a bad tag, a bad key length and a bad IV all reduce to
      // one outcome a caller can act on, and none of AGENTS.md §7's fields ever reach this
      // exception's message in the first place -- but the JDK's own message text is not vetted for
      // that promise, so it stops here rather than propagating.
      throw new IdentityDecryptionException();
    }
  }
}
