package dev.fondaria.eodjob.signoff;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The payload of one {@code close.signed-off.v1} message (Task 7.5a, {@code
 * contracts/events/close-signed-off-v1.schema.json}).
 *
 * <p>Built from a {@link CloseSignoff} row plus the two snapshot object keys and the month-end
 * flag, which are not on the row: the keys are on {@code workflow.snapshot_run} and the flag is a
 * {@code BusinessCalendar} question.
 *
 * <h2>The JSON is rendered field by field, not reflected off the record</h2>
 *
 * <p>Same discipline as {@code SnapshotManifest#render}: {@link #toJson} writes each field
 * explicitly and in a fixed order, so the wire shape is a property of this method and not of how a
 * serializer happened to order a record's components this release. {@code
 * CloseSignedOffEventConformanceTest} asserts it equals the committed example exactly.
 *
 * @param businessDate the close's date; also the Kafka message key
 * @param snapshotPositionCount open positions in the snapshot of record — the statement fan-out's
 *     control total
 * @param lastBusinessDayOfMonth whether Task 7.5b fans out a month's statements off this event
 */
public record CloseSignedOffEvent(
    LocalDate businessDate,
    Instant signedOffAt,
    java.util.UUID reconciliationRunId,
    java.util.UUID custodianRunId,
    java.util.UUID snapshotRunId,
    String snapshotObjectKey,
    String snapshotManifestKey,
    long snapshotPositionCount,
    boolean lastBusinessDayOfMonth) {

  public CloseSignedOffEvent {
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(signedOffAt, "signedOffAt must not be null");
    Objects.requireNonNull(reconciliationRunId, "reconciliationRunId must not be null");
    Objects.requireNonNull(custodianRunId, "custodianRunId must not be null");
    Objects.requireNonNull(snapshotRunId, "snapshotRunId must not be null");
    Objects.requireNonNull(snapshotObjectKey, "snapshotObjectKey must not be null");
    Objects.requireNonNull(snapshotManifestKey, "snapshotManifestKey must not be null");
    if (snapshotPositionCount < 0) {
      throw new IllegalArgumentException("snapshotPositionCount must not be negative");
    }
  }

  /** The Kafka message key: the business date, ISO-8601. */
  public String key() {
    return businessDate.toString();
  }

  /** The message body, canonical field order, UTF-8. */
  public String toJson() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(512);
    try (JsonGenerator json = new JsonFactory().createGenerator(out)) {
      json.writeStartObject();
      json.writeStringField("schemaVersion", CloseSignoffTopic.SCHEMA_VERSION);
      json.writeStringField("businessDate", businessDate.toString());
      json.writeStringField("signedOffAt", signedOffAt.toString());
      json.writeStringField("reconciliationRunId", reconciliationRunId.toString());
      json.writeStringField("custodianRunId", custodianRunId.toString());
      json.writeStringField("snapshotRunId", snapshotRunId.toString());
      json.writeStringField("snapshotObjectKey", snapshotObjectKey);
      json.writeStringField("snapshotManifestKey", snapshotManifestKey);
      json.writeNumberField("snapshotPositionCount", snapshotPositionCount);
      json.writeBooleanField("lastBusinessDayOfMonth", lastBusinessDayOfMonth);
      json.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException("could not render close.signed-off.v1", e);
    }
    return out.toString(StandardCharsets.UTF_8);
  }
}
