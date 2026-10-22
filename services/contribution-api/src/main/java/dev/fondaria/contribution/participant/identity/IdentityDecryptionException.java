package dev.fondaria.contribution.participant.identity;

import java.io.Serial;

/**
 * A wrapped data key or an encrypted identity field could not be opened: the wrong key, a corrupted
 * or truncated ciphertext, or (after Task 9.5's crypto-shredding) a key that no longer exists
 * anywhere to try. Carries no detail — the underlying JDK exception message is not vetted against
 * docs/ENGINEERING-RULES.md §7's never-log list, so it never reaches this exception's own message.
 */
final class IdentityDecryptionException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  IdentityDecryptionException() {
    super("Identity ciphertext could not be decrypted");
  }
}
