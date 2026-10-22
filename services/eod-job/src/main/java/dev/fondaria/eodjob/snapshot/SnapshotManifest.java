package dev.fondaria.eodjob.snapshot;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The manifest that travels with a day's snapshot (phase-07 Task 7.4).
 *
 * <p>It carries exactly what the phase document asks of it — record count, per-fund unit total,
 * per-fund value total, the file hash and the previous day's manifest hash — plus, for each fund,
 * the {@code units_in_issue} its unit total is checked against. Storing the number the tie was made
 * against, and not only the fact that it tied, is the difference between a manifest that can be
 * re-verified years later from data and one that can only be believed (AGENTS.md §4).
 *
 * <h2>There is no timestamp in here, deliberately</h2>
 *
 * <p>Every field is a property of the business date's book, never of when the pass happened. A
 * {@code generatedAt} would make the manifest hash change on every re-run, which breaks two things
 * at once: the re-run check for this date, and the chain link the <i>next</i> date records. When
 * the pass ran is on {@code workflow.snapshot_run}, which is where a question about the run rather
 * than about the book belongs.
 *
 * @param previousManifestSha256 the previous completed snapshot's manifest hash, or empty at the
 *     genesis of the chain — the first snapshot the platform ever takes, and the only legitimate
 *     absence
 */
public record SnapshotManifest(
    LocalDate businessDate,
    long positionCount,
    String positionsObjectKey,
    String positionsContentSha256,
    List<SnapshotFundTotal> fundTotals,
    Optional<String> previousManifestSha256) {

  /**
   * The schema version of this document. Present from the first one written: a manifest is read
   * years after it is produced, and a reader that cannot tell which shape it is holding has to
   * guess.
   */
  public static final String SCHEMA_VERSION = "v1";

  public SnapshotManifest {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(positionsObjectKey, "positionsObjectKey must not be null");
    Objects.requireNonNull(positionsContentSha256, "positionsContentSha256 must not be null");
    Objects.requireNonNull(previousManifestSha256, "previousManifestSha256 must not be null");
    fundTotals = List.copyOf(fundTotals);
  }

  /** Whether every fund's file total agrees with its {@code units_in_issue}. */
  public boolean ties() {
    return fundTotals.stream().allMatch(SnapshotFundTotal::ties);
  }

  /** The funds that did not tie, in the order the manifest lists them. */
  public List<SnapshotFundTotal> untiedFunds() {
    return fundTotals.stream().filter(total -> !total.ties()).toList();
  }

  /**
   * Renders the manifest to its stored bytes and their digest.
   *
   * <p>Field order is written out explicitly, for the same reason {@link PositionFileWriter} writes
   * a position's fields explicitly: the hash must be a property of the data, not of how a
   * serializer happened to order a record's components this release.
   */
  public RenderedObject render() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
    try (JsonGenerator json = new JsonFactory().createGenerator(out)) {
      json.writeStartObject();
      json.writeStringField("schemaVersion", SCHEMA_VERSION);
      json.writeStringField("businessDate", businessDate.toString());
      json.writeNumberField("positionCount", positionCount);
      json.writeStringField("positionsObjectKey", positionsObjectKey);
      json.writeStringField("positionsContentSha256", positionsContentSha256);
      if (previousManifestSha256.isPresent()) {
        json.writeStringField("previousManifestSha256", previousManifestSha256.get());
      } else {
        json.writeNullField("previousManifestSha256");
      }
      json.writeArrayFieldStart("funds");
      for (SnapshotFundTotal total : fundTotals) {
        json.writeStartObject();
        json.writeStringField("fundId", total.fundId().value().toString());
        json.writeNumberField("unitLedgerId", total.unitLedgerId());
        json.writeNumberField("positionCount", total.positionCount());
        json.writeNumberField("unitsScaled", total.units().scaled());
        json.writeStringField("currency", total.value().currency().name());
        json.writeNumberField("valueMinorUnits", total.value().cents());
        json.writeNumberField("unitsInIssueScaled", total.unitsInIssue().scaled());
        json.writeBooleanField("ties", total.ties());
        json.writeEndObject();
      }
      json.writeEndArray();
      json.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException("could not render the snapshot manifest", e);
    }
    return RenderedObject.of(out.toByteArray());
  }
}
