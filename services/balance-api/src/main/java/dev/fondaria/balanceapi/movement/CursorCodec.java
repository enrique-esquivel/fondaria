package dev.fondaria.balanceapi.movement;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns a {@link MovementCursor} into the opaque, tamper-checked token a client pages with, and
 * back (Task 6.3).
 *
 * <h2>Opaque, and checked rather than trusted</h2>
 *
 * <p>The token is {@code base64url(position) + "." + base64url(HMAC-SHA256(secret, position ‖ SEP ‖
 * queryFingerprint))}. The position is readable by anyone who base64-decodes it — that is not what
 * "opaque" buys. What it buys is that the client has no <i>documented</i> structure to build one
 * from, so the shape can change without breaking anyone, and the MAC is what makes an edited one
 * refused rather than obeyed.
 *
 * <p><b>The MAC covers the query, not only the position.</b> A cursor is issued for one
 * participant's history under one set of filters, and it verifies only when replayed against that
 * same query ({@link MovementQuery#fingerprint()}). Without that binding a token from a caller's
 * own page could be pasted onto a different {@code participantId} and would page happily through
 * somebody else's history — a keyset position is not access control, but it must not look like one
 * either. The fingerprint is <i>covered</i>, never carried: nothing identifying travels in the
 * token.
 *
 * <p><b>Every failure is the same failure.</b> A bad encoding, a truncated token, an edited
 * position and a wrong signature all raise {@link TamperedCursorException}, and the comparison is
 * {@link MessageDigest#isEqual} rather than {@code equals} — a byte-at-a-time comparison leaks
 * where the first difference is, which is exactly the feedback an attacker forging a token wants.
 *
 * <h2>The secret</h2>
 *
 * <p>Configuration with no default (docs/ENGINEERING-RULES.md §4). Rotating it invalidates every
 * outstanding cursor, which shows up as a {@code 400} on the next page rather than as wrong data —
 * the failure mode a rotation should have.
 */
public final class CursorCodec {

  private static final String ALGORITHM = "HmacSHA256";

  /** Field separator in the signed pre-image — ASCII unit separator, never in a decimal id. */
  private static final byte FIELD_SEPARATOR = 0x1F;

  private static final String FIELD_SEPARATOR_TEXT = String.valueOf((char) FIELD_SEPARATOR);

  /**
   * Bytes of MAC kept. 128 bits is far beyond forgeable and keeps the token short enough to sit in
   * a URL comfortably.
   */
  private static final int MAC_BYTES = 16;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] secret;

  public CursorCodec(String secret) {
    Objects.requireNonNull(secret, "secret must not be null");
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    if (this.secret.length == 0) {
      throw new IllegalArgumentException("cursor secret must not be empty");
    }
  }

  /** The token a client sends back to continue {@code query} after {@code cursor}. */
  public String encode(MovementQuery query, MovementCursor cursor) {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(cursor, "cursor must not be null");
    String position =
        String.join(
            FIELD_SEPARATOR_TEXT,
            cursor.ledgerTimestamp().toString(),
            cursor.transferId().toString(),
            Integer.toString(cursor.leg()));
    byte[] positionBytes = position.getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(positionBytes)
        + "."
        + ENCODER.encodeToString(mac(positionBytes, query.fingerprint()));
  }

  /**
   * @throws TamperedCursorException for every way {@code token} can fail to be a cursor this
   *     service issued for {@code query}
   */
  public MovementCursor decode(String token, MovementQuery query) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(query, "query must not be null");

    int separator = token.indexOf('.');
    if (separator < 0 || separator == token.length() - 1) {
      throw new TamperedCursorException("cursor is not a token this service issued");
    }
    byte[] positionBytes;
    byte[] presented;
    try {
      positionBytes = DECODER.decode(token.substring(0, separator));
      presented = DECODER.decode(token.substring(separator + 1));
    } catch (IllegalArgumentException e) {
      throw new TamperedCursorException("cursor is not a token this service issued");
    }
    if (!MessageDigest.isEqual(mac(positionBytes, query.fingerprint()), presented)) {
      throw new TamperedCursorException("cursor is not a token this service issued");
    }

    // Only now is the position parsed. A token that has not authenticated is never interpreted.
    String[] fields =
        new String(positionBytes, StandardCharsets.UTF_8).split(FIELD_SEPARATOR_TEXT, -1);
    if (fields.length != 3) {
      throw new TamperedCursorException("cursor is not a token this service issued");
    }
    try {
      return new MovementCursor(
          new BigInteger(fields[0]), new BigInteger(fields[1]), Integer.parseInt(fields[2]));
    } catch (IllegalArgumentException e) {
      throw new TamperedCursorException("cursor is not a token this service issued");
    }
  }

  private byte[] mac(byte[] positionBytes, String fingerprint) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      mac.update(positionBytes);
      mac.update(FIELD_SEPARATOR);
      mac.update(fingerprint.getBytes(StandardCharsets.UTF_8));
      byte[] full = mac.doFinal();
      byte[] truncated = new byte[MAC_BYTES];
      System.arraycopy(full, 0, truncated, 0, MAC_BYTES);
      return truncated;
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is required of every Java platform", e);
    }
  }
}
