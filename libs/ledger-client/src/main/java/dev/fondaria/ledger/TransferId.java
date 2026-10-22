package dev.fondaria.ledger;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * Opaque ledger transfer identifier, carried as the two longs that make up a TigerBeetle u128.
 * Derived deterministically from an idempotency key (Task 2.3) — never generated randomly in a
 * request path.
 */
public record TransferId(long mostSignificantBits, long leastSignificantBits) {

  public static TransferId of(long mostSignificantBits, long leastSignificantBits) {
    return new TransferId(mostSignificantBits, leastSignificantBits);
  }

  /**
   * The canonical unsigned base-10 form of the u128 — how this transfer's id appears on the CDC
   * stream and therefore in {@code projection.transfer.transfer_id} ({@link
   * AccountId#asUnsignedDecimal()}'s counterpart; see its javadoc for why {@code
   * mostSignificantBits} is the high half). Task 11.2's trace-correlation key is keyed on this form
   * specifically because it is the one identifier both {@code contribution-api} (which derives a
   * {@link TransferId} before submission) and {@code projection-worker} (which decodes one off the
   * wire as a {@code String}) already agree on without a lookup.
   */
  public String asUnsignedDecimal() {
    byte[] bigEndian =
        ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array();
    return new BigInteger(1, bigEndian).toString();
  }
}
