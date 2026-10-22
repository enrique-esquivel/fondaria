package dev.fondaria.contribution.cashreceipt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The canonical form of a cash-receipt request, and its SHA-256.
 *
 * <p><b>What it is for.</b> An idempotency key promises "this is the same request as before". The
 * fingerprint is what lets that promise be checked: a key resubmitted with a different amount,
 * organization or value date is key reuse, and answered with 422 rather than silently treated as a
 * retry of something else. It is stored on {@code workflow.cash_receipt}, so the check is answered
 * from PostgreSQL and survives a cold or unavailable Redis — the cache is an optimisation, never
 * the thing that keeps two different requests apart.
 *
 * <p><b>Why the canonical form is spelled out rather than "hash the JSON".</b> Two byte-identical
 * requests must fingerprint identically for years, across formatter changes, field reordering,
 * whitespace, and any future rewrite of the request DTO. Hashing the received JSON would make all
 * of those a false 422. So the fields are written here in a fixed order, in a fixed encoding, with
 * the amount as integer cents — never a float or a decimal string (AGENTS.md §4).
 *
 * <p><b>The version prefix is load-bearing.</b> Changing the canonical form changes every future
 * fingerprint while stored ones keep the old form. The prefix makes that visible instead of silent:
 * a stored {@code v1} fingerprint can be recognised and recomputed under {@code v1} rather than
 * compared against a {@code v2} value it was never going to match.
 *
 * <p><b>Not a signature.</b> This proves two requests are the same, not that anyone authorised
 * either — there is no key material and no authenticated subject until Phase 9. Signing cash
 * movements is an open question (docs/01-DECISIONS-TO-DISCUSS.md G16); when it is answered, these
 * are the bytes a signature covers, which is why {@code operatorId} is inside them.
 */
public final class CashReceiptFingerprint {

  private static final String VERSION = "v1";

  private CashReceiptFingerprint() {}

  /** Lowercase-hex SHA-256 of {@link #canonicalForm}. */
  public static String of(RecordCashReceiptCommand command) {
    byte[] digest = sha256(canonicalForm(command).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  /**
   * The exact bytes hashed. Package-private and separate from {@link #of} so a test can pin the
   * canonical form itself: a change to it that nobody noticed would revalue every stored
   * fingerprint, and a test asserting only "the hash is stable" would not catch it.
   *
   * <p>{@code idempotencyKey} is deliberately absent. The fingerprint answers "is this the same
   * request under the same key", so the key identifies the row rather than forming part of what is
   * compared within it.
   */
  static String canonicalForm(RecordCashReceiptCommand command) {
    return String.join(
        "\n",
        VERSION,
        "organizationId=" + command.organizationId(),
        "amountMinorUnits=" + command.amount().cents(),
        "currency=" + command.amount().currency().name(),
        "valueDate=" + command.valueDate(),
        "bankReference=" + command.bankReference(),
        "operatorId=" + command.operatorId());
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      // Every JVM ships SHA-256; this is unreachable rather than a case to handle.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
