package dev.fondaria.statementworker.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.statementworker.ParticipantKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * Reads one of Task 7.4's dense-snapshot objects — the writer side is {@code PositionFileWriter} —
 * and sums it to one value per participant (Task 7.5b, {@code ADR-0022} §4).
 *
 * <p><b>Streamed, never buffered whole.</b> The file can run to a million lines (Task 7.4's own
 * acceptance criterion); this reads it one gzip-decompressed line at a time and keeps only the
 * running per-participant totals, never the individual position rows. Memory is bounded by the
 * number of distinct participants, not the number of lines.
 *
 * <p>Field names match {@code PositionFileWriter#write} exactly — this is the reader half of that
 * writer, in a different service, so nothing here may drift from that shape independently. Unlike
 * the writer, field <i>order</i> does not matter on read: nothing here computes a hash of the
 * input, so {@code ObjectMapper}'s ordinary tolerant parsing is the right tool, not the explicit
 * field-by-field discipline {@code PositionFileWriter} and {@code SnapshotManifest} use for bytes
 * that must hash identically.
 */
@Component
public final class PositionFileReader {

  private final ObjectMapper objectMapper;

  public PositionFileReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Reads and sums a gzipped NDJSON positions file, closing {@code gzipped} when done. */
  public AggregatedPositions read(InputStream gzipped) {
    Map<ParticipantKey, Money> valueByParticipant = new HashMap<>();
    long positionCount = 0;
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new GZIPInputStream(gzipped), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode node = objectMapper.readTree(line);
        ParticipantKey key =
            new ParticipantKey(
                new OrganizationId(UUID.fromString(node.get("organizationId").asText())),
                node.get("participantInternalRef").asText());
        Money value =
            Money.of(
                node.get("valueMinorUnits").asLong(),
                CurrencyCode.valueOf(node.get("currency").asText()));
        valueByParticipant.merge(key, value, Money::plus);
        positionCount++;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read a snapshot positions file", e);
    }
    return new AggregatedPositions(valueByParticipant, positionCount);
  }
}
