package dev.fondaria.eodjob.snapshot;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import dev.fondaria.common.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Streams the dense snapshot as gzipped newline-delimited JSON, hashing the lines as they go and
 * accumulating each fund's totals on the way past.
 *
 * <h2>Why NDJSON and not Parquet</h2>
 *
 * <p>phase-07 Task 7.4 as written says Parquet, registered in Iceberg, under object lock — but
 * Iceberg registration is Task 8.3 and object lock is Task 8.1, and Phase 8 is skipped (BACKLOG.md
 * flags this overlap directly). Trimmed to plain object storage on the platform owner's call, and
 * none of this task's six acceptance criteria distinguish the formats: they are about record count,
 * dormant participants, hash stability, the per-fund tie and the window at a million positions.
 * What NDJSON costs is columnar reads nothing in v1 performs; what it buys is no {@code parquet-mr}
 * and no Hadoop dependency tail in a service that has neither.
 *
 * <h2>What makes the bytes reproducible</h2>
 *
 * <p>Three things, and the acceptance criterion "a re-run produces an identical hash" rests on all
 * three:
 *
 * <ol>
 *   <li><b>Row order.</b> Positions arrive in the order the keyset scan over {@code
 *       workflow.allotment} yields them — {@code (organization_id, participant_internal_ref,
 *       fund_id)} ascending — which is a total order over the file's own key. This class does not
 *       sort and must not: sorting a million rows in memory to reproduce an order the index already
 *       provides would be the one part of the pass that does not fit the window.
 *   <li><b>Field order.</b> Written explicitly below, field by field, rather than by reflecting
 *       over a record. A serializer that decided its own order would make the hash a property of a
 *       library version.
 *   <li><b>The digest is over the plaintext.</b> See {@link PositionFile#contentSha256()} — the
 *       reason it is not over the gzip is the whole difference between a check that fires on real
 *       divergence and one that also fires on a JDK upgrade.
 * </ol>
 *
 * <h2>Bounded, and loudly</h2>
 *
 * <p>The compressed file is built in memory. That is a deliberate simplification over spooling to
 * disk — a million positions gzip to tens of megabytes, and a temporary file would add a writable
 * path, a cleanup path and a failure mode on a container whose root filesystem is read-only. It is
 * bounded by {@code maximumObjectBytes}: past it the pass fails rather than growing until the
 * process dies, because an {@code OutOfMemoryError} during a close tells an operator nothing about
 * which of the five steps was running.
 */
public final class PositionFileWriter implements AutoCloseable {

  private final long maximumObjectBytes;
  private final ByteArrayOutputStream compressed;
  private final GZIPOutputStream gzip;
  private final MessageDigest digest;
  private final JsonGenerator json;
  private final Map<Long, FundTally> talliesByLedger = new HashMap<>();

  private long positionCount;
  private boolean finished;

  private PositionFileWriter(long maximumObjectBytes) throws IOException {
    this.maximumObjectBytes = maximumObjectBytes;
    this.compressed = new ByteArrayOutputStream(1 << 16);
    this.gzip = new GZIPOutputStream(compressed, 1 << 16);
    this.digest = RenderedObject.newDigest();
    // The digest sits between the generator and the compressor, so it sees the NDJSON exactly as
    // written and never the deflate output.
    this.json = new JsonFactory().createGenerator(new DigestOutputStream(gzip, digest));
  }

  /** Opens a writer. The caller closes it; {@link #finish()} does not. */
  public static PositionFileWriter open(long maximumObjectBytes) {
    if (maximumObjectBytes <= 0) {
      throw new IllegalArgumentException("maximumObjectBytes must be positive");
    }
    try {
      return new PositionFileWriter(maximumObjectBytes);
    } catch (IOException e) {
      throw new UncheckedIOException("could not open the snapshot file writer", e);
    }
  }

  /**
   * Writes one position as one line, and folds it into its fund's running totals.
   *
   * <p>Every quantity is written as an integer — cents, units at 1e-8, NAV at 1e-8 — and never as a
   * decimal string or a JSON floating-point number (docs/ENGINEERING-RULES.md §4). {@code
   * unrealisedGain} is signed and is written as it stands; a negative one is a correct answer on a
   * falling NAV, not an error to clamp.
   */
  public void write(SnapshotPosition position) {
    if (finished) {
      throw new IllegalStateException("the snapshot file is already finished");
    }
    try {
      json.writeStartObject();
      json.writeStringField("organizationId", position.organizationId().value().toString());
      json.writeStringField("participantInternalRef", position.participantInternalRef());
      json.writeStringField("fundId", position.fundId().value().toString());
      json.writeNumberField("unitLedgerId", position.unitLedgerId());
      json.writeNumberField("unitsScaled", position.units().scaled());
      json.writeNumberField("navScaled", position.nav().scaled());
      json.writeNumberField("navVersionId", position.navVersionId());
      json.writeStringField("currency", position.value().currency().name());
      json.writeNumberField("valueMinorUnits", position.value().cents());
      json.writeNumberField("costBasisMinorUnits", position.costBasis().cents());
      json.writeNumberField("unrealisedGainMinorUnits", position.unrealisedGain().cents());
      json.writeEndObject();
      json.writeRaw('\n');
    } catch (IOException e) {
      throw new UncheckedIOException("could not write a snapshot position", e);
    }

    positionCount = Math.addExact(positionCount, 1L);
    talliesByLedger.compute(
        position.unitLedgerId(),
        (unitLedgerId, running) ->
            (running == null
                    ? FundTally.opening(
                        position.fundId(), unitLedgerId, Money.of(0L, position.value().currency()))
                    : running)
                .plus(position));

    if (compressed.size() > maximumObjectBytes) {
      throw new SnapshotTooLargeException(compressed.size(), maximumObjectBytes);
    }
  }

  /**
   * Closes the JSON and the compressor and returns the finished file.
   *
   * <p>The digest is taken only here, after the generator has flushed every buffered line — reading
   * it earlier would hash a prefix of the file and produce a value that looks entirely legitimate.
   */
  public PositionFile finish() {
    if (finished) {
      throw new IllegalStateException("the snapshot file is already finished");
    }
    try {
      // Closing the generator closes the digest stream, which closes the compressor, which flushes
      // the deflate trailer. Jackson's AUTO_CLOSE_TARGET is on, so this one call is the whole
      // shutdown — an explicit gzip.finish() after it would be a no-op that reads as load-bearing.
      json.close();
    } catch (IOException e) {
      throw new UncheckedIOException("could not finish the snapshot file", e);
    }
    finished = true;

    byte[] bytes = compressed.toByteArray();
    if (bytes.length > maximumObjectBytes) {
      throw new SnapshotTooLargeException(bytes.length, maximumObjectBytes);
    }
    List<FundTally> tallies = new ArrayList<>(talliesByLedger.values());
    tallies.sort(Comparator.comparingLong(FundTally::unitLedgerId));
    return new PositionFile(bytes, RenderedObject.hexOf(digest.digest()), positionCount, tallies);
  }

  @Override
  public void close() {
    if (finished) {
      return;
    }
    // An abandoned writer — the pass threw partway through. Nothing is salvageable and nothing
    // downstream may see it, so the stream chain is simply released.
    try {
      json.close();
    } catch (IOException e) {
      throw new UncheckedIOException("could not release the snapshot file writer", e);
    }
  }
}
