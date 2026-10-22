package dev.fondaria.statementworker.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.statementworker.ParticipantKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads what {@code eod-job}'s {@code PositionFileWriter} actually writes — one NDJSON line per
 * (organization, participant, fund) position — and sums it per participant.
 */
class PositionFileReaderTest {

  private final PositionFileReader reader = new PositionFileReader(new ObjectMapper());

  @Test
  @DisplayName("two positions for the same participant, in different funds, are summed together")
  void sumsAcrossFundsForOneParticipant() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    UUID fundA = UUID.randomUUID();
    UUID fundB = UUID.randomUUID();
    String participantRef = "internal-ref-1";

    byte[] gzipped =
        gzip(
            positionLine(orgId, participantRef, fundA, 100_000L),
            positionLine(orgId, participantRef, fundB, 250_000L),
            positionLine(orgId, "internal-ref-2", fundA, 50_000L));

    AggregatedPositions result = reader.read(new ByteArrayInputStream(gzipped));

    assertThat(result.positionCount()).isEqualTo(3);
    assertThat(result.valueByParticipant())
        .hasSize(2)
        .containsEntry(
            new ParticipantKey(orgId, participantRef),
            dev.fondaria.common.Money.of(350_000L, dev.fondaria.common.CurrencyCode.MXN))
        .containsEntry(
            new ParticipantKey(orgId, "internal-ref-2"),
            dev.fondaria.common.Money.of(50_000L, dev.fondaria.common.CurrencyCode.MXN));
  }

  @Test
  @DisplayName("an empty file aggregates to nothing, not an error")
  void emptyFileAggregatesToNothing() {
    AggregatedPositions result = reader.read(new ByteArrayInputStream(gzip()));
    assertThat(result.positionCount()).isZero();
    assertThat(result.valueByParticipant()).isEmpty();
  }

  private static String positionLine(
      OrganizationId orgId, String participantRef, UUID fundId, long valueMinorUnits) {
    return """
        {"organizationId":"%s","participantInternalRef":"%s","fundId":"%s","unitLedgerId":1,\
        "unitsScaled":100000000,"navScaled":100000000,"navVersionId":1,"currency":"MXN",\
        "valueMinorUnits":%d,"costBasisMinorUnits":0,"unrealisedGainMinorUnits":0}"""
        .formatted(orgId.value(), participantRef, fundId, valueMinorUnits);
  }

  private static byte[] gzip(String... lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      for (String line : lines) {
        gzip.write(line.getBytes(StandardCharsets.UTF_8));
        gzip.write('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
