package dev.fondaria.projection.consume;

import java.math.BigInteger;
import java.time.Instant;

/**
 * Parsing helper shared by the projection sinks. TigerBeetle's {@code timestamp} is a u64
 * nanoseconds-since-epoch value, carried on the wire as a canonical base-10 string (as {@code
 * LedgerEventDecoder} produces it). Both the transfer projection (Task 5.4) and the sparse unit
 * position projection (Task 5.5) turn it into a business date through {@code BusinessCalendar}, so
 * the conversion lives in one place.
 */
final class LedgerTimestamps {

  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

  private LedgerTimestamps() {}

  /** A u64 nanoseconds-since-epoch value (as a base-10 string) to an {@link Instant}. */
  static Instant toInstant(String nanosSinceEpoch) {
    BigInteger[] secondsAndNanos =
        new BigInteger(nanosSinceEpoch).divideAndRemainder(NANOS_PER_SECOND);
    return Instant.ofEpochSecond(
        secondsAndNanos[0].longValueExact(), secondsAndNanos[1].longValueExact());
  }
}
