package dev.fondaria.statementworker.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The decoded payload of one {@code close.signed-off.v1} message ({@code
 * contracts/events/close-signed-off-v1.schema.json}, Task 7.5a).
 *
 * <p>The write side of this record lives in {@code eod-job}'s {@code signoff} package; this is the
 * read side, in a different service, matching {@code ADR-0021}'s "services do not depend on each
 * other's source trees". {@link #schemaVersion} is checked on decode: a version this service does
 * not recognise is treated exactly like a malformed message — dead-lettered and halted, never a
 * best-effort parse (the schema's own {@code description} says so).
 *
 * @param snapshotRunId which {@code workflow.snapshot_run} row produced {@link #snapshotObjectKey}
 *     — used to read that run's {@code started_at} as the read-consistency cutoff for {@code
 *     workflow.allotment} (see {@code StatementScheduler}): an allotment written after the closing
 *     snapshot's pass began must not be counted as this period's contribution, because its units
 *     are not reflected in the closing snapshot it would otherwise be reconciled against.
 */
public record CloseSignedOffEvent(
    String schemaVersion,
    LocalDate businessDate,
    Instant signedOffAt,
    UUID snapshotRunId,
    String snapshotObjectKey,
    long snapshotPositionCount,
    boolean lastBusinessDayOfMonth) {

  public static final String SUPPORTED_SCHEMA_VERSION = "v1";

  public CloseSignedOffEvent {
    Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
    Objects.requireNonNull(businessDate, "businessDate must not be null");
    Objects.requireNonNull(signedOffAt, "signedOffAt must not be null");
    Objects.requireNonNull(snapshotRunId, "snapshotRunId must not be null");
    Objects.requireNonNull(snapshotObjectKey, "snapshotObjectKey must not be null");
  }

  /** Decodes one message body. Never a best-effort parse: any missing field fails the decode. */
  public static CloseSignedOffEvent fromJson(ObjectMapper objectMapper, String json) {
    try {
      JsonNode node = objectMapper.readTree(json);
      return new CloseSignedOffEvent(
          requireText(node, "schemaVersion"),
          LocalDate.parse(requireText(node, "businessDate")),
          Instant.parse(requireText(node, "signedOffAt")),
          UUID.fromString(requireText(node, "snapshotRunId")),
          requireText(node, "snapshotObjectKey"),
          requireNode(node, "snapshotPositionCount").asLong(),
          requireNode(node, "lastBusinessDayOfMonth").asBoolean());
    } catch (IOException | RuntimeException e) {
      throw new MalformedCloseSignedOffEventException("could not decode close.signed-off.v1", e);
    }
  }

  public boolean isSupportedVersion() {
    return SUPPORTED_SCHEMA_VERSION.equals(schemaVersion);
  }

  /** The calendar month this event's business date falls in, {@code YYYY-MM}. */
  public String period() {
    return businessDate.toString().substring(0, 7);
  }

  private static String requireText(JsonNode node, String field) {
    return requireNode(node, field).asText();
  }

  private static JsonNode requireNode(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("close.signed-off.v1 message is missing field " + field);
    }
    return value;
  }
}
