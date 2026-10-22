package dev.fondaria.statementworker.render;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bytes about to be written to object storage, with the SHA-256 that identifies them. A copy of
 * {@code eod-job}'s {@code snapshot.RenderedObject} in this service — the two do not share a
 * dependency, matching {@code ADR-0021}'s "services do not depend on each other's source trees".
 *
 * <p><b>Compare {@link #sha256()}, never {@link #bytes()}.</b> This is a record over an array, so
 * its generated {@code equals} is reference equality on the array and means nothing.
 */
public record RenderedObject(byte[] bytes, String sha256) {

  public RenderedObject {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(sha256, "sha256 must not be null");
  }

  public static RenderedObject of(byte[] bytes) {
    return new RenderedObject(bytes, hexDigestOf(bytes));
  }

  public static String hexDigestOf(byte[] bytes) {
    return HexFormat.of().formatHex(newDigest().digest(bytes));
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available in this JVM", e);
    }
  }
}
