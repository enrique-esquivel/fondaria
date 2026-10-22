package dev.fondaria.eodjob.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bytes about to be written to object storage, with the SHA-256 that identifies them.
 *
 * <p><b>Compare {@link #sha256()}, never {@link #bytes()}.</b> This is a record over an array, so
 * its generated {@code equals} is reference equality on the array and means nothing; the digest is
 * the identity. Nothing in this package compares two of these directly — {@code SnapshotService}
 * compares a fresh digest against the one recorded on an earlier run, which is a string comparison
 * against a database column.
 *
 * @param bytes the exact bytes stored, never mutated after construction
 * @param sha256 lowercase hex, matching the form {@code workflow.snapshot_run} pins with a CHECK
 */
public record RenderedObject(byte[] bytes, String sha256) {

  public RenderedObject {
    Objects.requireNonNull(bytes, "bytes must not be null");
    Objects.requireNonNull(sha256, "sha256 must not be null");
  }

  /** Hashes the bytes as given. The caller keeps ownership; nothing here copies or trims. */
  public static RenderedObject of(byte[] bytes) {
    return new RenderedObject(bytes, hexDigestOf(bytes));
  }

  /** Lowercase hex SHA-256 of the given content. */
  public static String hexDigestOf(byte[] bytes) {
    return hexOf(newDigest().digest(bytes));
  }

  /**
   * Renders already-computed digest bytes as lowercase hex.
   *
   * <p>Separate from {@link #hexDigestOf} because a streaming writer finishes a {@code
   * MessageDigest} it has been feeding all along, and passing those 32 bytes to {@code hexDigestOf}
   * would hash the hash — producing a perfectly well-formed digest of the wrong thing.
   */
  public static String hexOf(byte[] digestBytes) {
    return HexFormat.of().formatHex(digestBytes);
  }

  /**
   * A fresh digest.
   *
   * <p>{@code SHA-256} is required of every Java platform, so the checked exception cannot fire —
   * but it is turned into an {@code IllegalStateException} rather than swallowed, because a
   * snapshot that could not hash its own file must not be recorded as one that did.
   */
  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available in this JVM", e);
    }
  }
}
