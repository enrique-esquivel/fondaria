package dev.fondaria.contribution.staff;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Deterministic cryptographic SHA-256 hash chaining for staff action audit entries. */
public final class StaffActionAuditHash {

  public static final String GENESIS_HASH = "0".repeat(64);

  private StaffActionAuditHash() {}

  public static String computeHash(
      String prevHash,
      long sequence,
      UUID actionId,
      StaffActionType actionType,
      StaffActionAuditEventType eventType,
      String actor,
      Instant recordedAt,
      String payloadJson) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String input =
          (prevHash != null ? prevHash : GENESIS_HASH)
              + ":"
              + sequence
              + ":"
              + actionId
              + ":"
              + actionType.name()
              + ":"
              + eventType.name()
              + ":"
              + actor
              + ":"
              + recordedAt.toString()
              + ":"
              + payloadJson;
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
