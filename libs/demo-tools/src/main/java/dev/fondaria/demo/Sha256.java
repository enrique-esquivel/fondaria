package dev.fondaria.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one checksum this module computes: lowercase-hex SHA-256 over exact file bytes, matching
 * `checksumSha256` in both `contribution-file-v2.md` §4 and `valuation-file-v1.md` §4.
 */
public final class Sha256 {

  private Sha256() {}

  public static String hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String hex(String content) {
    return hex(content.getBytes(StandardCharsets.UTF_8));
  }
}
