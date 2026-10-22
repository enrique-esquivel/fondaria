package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.Units;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bytes the snapshot is made of, and what makes them reproducible. */
class PositionFileWriterTest {

  private static final long ONE_MIB = 1024L * 1024L;

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final FundId fundId = new FundId(UUID.randomUUID());

  @Test
  @DisplayName("writes one JSON object per line, with every quantity an integer")
  void writesOneObjectPerLine() {
    PositionFile file = write(position("P-001", 100_00000000L, 1_00000000L, 9_000L));

    List<String> lines = linesOf(file);
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0))
        .isEqualTo(
            "{\"organizationId\":\""
                + organizationId.value()
                + "\",\"participantInternalRef\":\"P-001\",\"fundId\":\""
                + fundId.value()
                + "\",\"unitLedgerId\":1001,\"unitsScaled\":10000000000,\"navScaled\":100000000,"
                + "\"navVersionId\":7,\"currency\":\"MXN\",\"valueMinorUnits\":10000,"
                + "\"costBasisMinorUnits\":9000,\"unrealisedGainMinorUnits\":1000}");
    assertThat(lines.get(0))
        .as("no decimal point anywhere near an amount (AGENTS.md §4)")
        .doesNotContain(".");
  }

  @Test
  @DisplayName("writes the same bytes for the same positions in the same order")
  void isReproducible() {
    List<SnapshotPosition> positions =
        List.of(
            position("P-001", 100_00000000L, 1_07000000L, 9_000L),
            position("P-002", 33_33333333L, 1_07000000L, 3_100L));

    PositionFile first = write(positions.toArray(SnapshotPosition[]::new));
    PositionFile second = write(positions.toArray(SnapshotPosition[]::new));

    assertThat(second.contentSha256()).isEqualTo(first.contentSha256());
    assertThat(second.gzippedBytes()).isEqualTo(first.gzippedBytes());
  }

  @Test
  @DisplayName("writes different bytes when a single unit at 1e-8 changes")
  void differsOnTheSmallestChange() {
    PositionFile first = write(position("P-001", 100_00000000L, 1_00000000L, 9_000L));
    PositionFile second = write(position("P-001", 100_00000001L, 1_00000000L, 9_000L));

    assertThat(second.contentSha256()).isNotEqualTo(first.contentSha256());
  }

  @Test
  @DisplayName("escapes a participant reference that would otherwise break the JSON")
  void escapesParticipantReferences() {
    PositionFile file = write(position("P-\"001\"\n", 1_00000000L, 1_00000000L, 100L));

    List<String> lines = linesOf(file);
    assertThat(lines).as("an embedded newline must not become a second record").hasSize(1);
    assertThat(lines.get(0)).contains("\\\"001\\\"\\n");
  }

  @Test
  @DisplayName("carries a negative unrealised gain through unchanged")
  void carriesALossThrough() {
    // 100 units bought at 1.00, now worth 0.60.
    PositionFile file = write(position("P-001", 100_00000000L, 60000000L, 10_000L));

    assertThat(linesOf(file).get(0)).contains("\"unrealisedGainMinorUnits\":-4000");
  }

  @Test
  @DisplayName("tallies each fund's rows, units and value as it goes")
  void talliesPerFund() {
    PositionFile file =
        write(
            position("P-001", 100_00000000L, 1_00000000L, 0L),
            position("P-002", 50_00000000L, 1_00000000L, 0L));

    assertThat(file.positionCount()).isEqualTo(2L);
    assertThat(file.fundTallies()).hasSize(1);
    FundTally tally = file.fundTallies().get(0);
    assertThat(tally.positionCount()).isEqualTo(2L);
    assertThat(tally.units()).isEqualTo(Units.of(150_00000000L));
    assertThat(tally.value()).isEqualTo(Money.of(15_000L, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("an empty snapshot is a valid, reproducible, empty file")
  void anEmptyFileIsValid() {
    PositionFile file = write();

    assertThat(file.positionCount()).isZero();
    assertThat(file.fundTallies()).isEmpty();
    assertThat(linesOf(file)).isEmpty();
    assertThat(file.contentSha256())
        .as("SHA-256 of nothing at all — the digest is over the NDJSON, which is empty")
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  @DisplayName("fails naming both numbers rather than growing past its ceiling")
  void refusesToGrowPastItsCeiling() {
    assertThatExceptionOfType(SnapshotTooLargeException.class)
        .isThrownBy(
            () -> {
              try (PositionFileWriter writer = PositionFileWriter.open(64L)) {
                for (int i = 0; i < 100_000; i++) {
                  writer.write(position("P-" + i, 1_00000000L, 1_00000000L, 100L));
                }
                writer.finish();
              }
            })
        .withMessageContaining("SNAPSHOT_MAX_OBJECT_BYTES");
  }

  private PositionFile write(SnapshotPosition... positions) {
    try (PositionFileWriter writer = PositionFileWriter.open(ONE_MIB)) {
      for (SnapshotPosition position : positions) {
        writer.write(position);
      }
      return writer.finish();
    }
  }

  private SnapshotPosition position(
      String participantInternalRef, long unitsScaled, long navScaled, long costBasisCents) {
    return SnapshotPosition.of(
        organizationId,
        participantInternalRef,
        fundId,
        1001L,
        Units.of(unitsScaled),
        Nav.of(navScaled, CurrencyCode.MXN),
        7L,
        Money.of(costBasisCents, CurrencyCode.MXN));
  }

  private static List<String> linesOf(PositionFile file) {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(file.gzippedBytes()))) {
      String ndjson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return ndjson.isEmpty() ? List.of() : List.of(ndjson.split("\n"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
