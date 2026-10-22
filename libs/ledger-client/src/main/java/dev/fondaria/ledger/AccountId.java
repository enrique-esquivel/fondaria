package dev.fondaria.ledger;

import java.math.BigInteger;
import java.nio.ByteBuffer;

/**
 * Opaque ledger account identifier, carried as the two longs that make up a TigerBeetle u128
 * without ever exposing the vendor's own id type in application code.
 */
public record AccountId(long mostSignificantBits, long leastSignificantBits) {

  public static AccountId of(long mostSignificantBits, long leastSignificantBits) {
    return new AccountId(mostSignificantBits, leastSignificantBits);
  }

  /**
   * The canonical unsigned base-10 form of the u128 — how this account's id appears on the CDC
   * stream and therefore in {@code projection.transfer.debit_account_id} / {@code
   * credit_account_id} (Task 6.3, {@code TenantRef#asUnsignedDecimal}'s 128-bit counterpart).
   *
   * <p><b>{@code mostSignificantBits} is the high half</b>, matching {@code
   * UInt128.MostSignificant}: the adapter's two-long writers take {@code (least, most)} and
   * TigerBeetle composes {@code (most << 64) | least}, so the numeric value of an id derived by
   * {@link AccountIdDeriver} is exactly the numeric value of the UUID it was derived from. Getting
   * this backwards would not fail — it would look up movements under an id nothing ever wrote, and
   * report every participant as having no history, which is the worst shape a failure can take
   * here.
   */
  public String asUnsignedDecimal() {
    byte[] bigEndian =
        ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array();
    return new BigInteger(1, bigEndian).toString();
  }
}
