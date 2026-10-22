package dev.fondaria.eodjob.snapshot;

import java.util.List;
import java.util.Objects;

/**
 * The finished positions file: the bytes to store, the digest that identifies them, and the
 * per-fund totals accumulated while writing.
 *
 * <h2>The digest is over the NDJSON, not over the gzip</h2>
 *
 * <p>{@link #contentSha256} hashes the <b>uncompressed</b> lines; {@link #gzippedBytes} is what
 * goes to object storage. They are deliberately not the same thing, and the digest is the plaintext
 * one for a reason that decides whether the re-run check is trustworthy: a compressor's output is a
 * property of the compressor, not of the data. {@code java.util.zip.Deflater} is free to emit
 * different bytes for identical input across JDK versions or a changed compression level, and
 * hashing that would turn an ordinary runtime upgrade into "the snapshot for yesterday no longer
 * matches", which reads exactly like corruption and is not.
 *
 * <p>Hashing the content instead means the check asserts what it is supposed to assert — <i>the
 * same positions, at the same prices, in the same order</i> — and nothing else. Integrity of the
 * stored object is not lost by this: the gzip container carries its own CRC-32 over the same
 * plaintext, so a truncated or corrupted object fails to decompress before its digest is ever
 * computed.
 *
 * @param gzippedBytes the object's bytes, gzipped NDJSON
 * @param contentSha256 lowercase hex SHA-256 of the uncompressed NDJSON
 * @param positionCount rows written — the control total Task 7.5 counts statements against
 * @param fundTallies one entry per fund the file holds rows for, ordered by unit ledger id
 */
public record PositionFile(
    byte[] gzippedBytes, String contentSha256, long positionCount, List<FundTally> fundTallies) {

  public PositionFile {
    Objects.requireNonNull(gzippedBytes, "gzippedBytes must not be null");
    Objects.requireNonNull(contentSha256, "contentSha256 must not be null");
    fundTallies = List.copyOf(fundTallies);
    if (positionCount < 0) {
      throw new IllegalArgumentException("positionCount must not be negative");
    }
  }
}
