package dev.fondaria.contribution.participant.identity;

import java.util.Objects;

/**
 * The decoded master key (KEK) bytes, validated once at startup and held for the life of the
 * service. Never rendered: {@link #toString()} deliberately does not include {@link #bytes()}.
 */
public final class ParticipantIdentityMasterKey {

  private final byte[] bytes;

  public ParticipantIdentityMasterKey(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    if (bytes.length != IdentityCipher.DATA_KEY_BYTES) {
      throw new IllegalArgumentException(
          "master key must decode to " + IdentityCipher.DATA_KEY_BYTES + " bytes (AES-256)");
    }
    this.bytes = bytes.clone();
  }

  /**
   * A defensive copy every time — the field itself must never be handed out live, since a caller
   * mutating the returned array would corrupt every future encryption without this class knowing.
   */
  byte[] bytes() {
    return bytes.clone();
  }

  @Override
  public String toString() {
    return "ParticipantIdentityMasterKey[REDACTED]";
  }
}
