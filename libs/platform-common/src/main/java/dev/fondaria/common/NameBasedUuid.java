package dev.fondaria.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * RFC 4122 §4.3 name-based UUIDs, version 5 (SHA-1). The JDK ships only version 3 (MD5), so this is
 * the platform's one implementation.
 *
 * <p>Deliberately shared rather than reimplemented per caller. Ledger transfer ids, ledger account
 * ids and organization ids are all derived this way, and every one of them has to keep deriving the
 * same value for the life of the system — across JVMs, restarts and future versions. Two
 * implementations that drift apart by a byte silently repoint ids at nothing.
 */
public final class NameBasedUuid {

  private NameBasedUuid() {}

  /**
   * @param namespace the namespace UUID; must come from configuration and be stable forever
   * @param name the name to derive within that namespace
   */
  public static UUID v5(UUID namespace, String name) {
    Objects.requireNonNull(namespace, "namespace must not be null");
    Objects.requireNonNull(name, "name must not be null");

    MessageDigest sha1;
    try {
      sha1 = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
    ByteBuffer namespaceBytes = ByteBuffer.allocate(16);
    namespaceBytes.putLong(namespace.getMostSignificantBits());
    namespaceBytes.putLong(namespace.getLeastSignificantBits());
    sha1.update(namespaceBytes.array());
    sha1.update(name.getBytes(StandardCharsets.UTF_8));
    byte[] hash = sha1.digest();

    hash[6] &= 0x0f;
    hash[6] |= 0x50; // version 5
    hash[8] &= 0x3f;
    hash[8] |= (byte) 0x80; // RFC 4122 variant

    long msb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (hash[i] & 0xffL);
    }
    long lsb = 0;
    for (int i = 8; i < 16; i++) {
      lsb = (lsb << 8) | (hash[i] & 0xffL);
    }
    return new UUID(msb, lsb);
  }
}
