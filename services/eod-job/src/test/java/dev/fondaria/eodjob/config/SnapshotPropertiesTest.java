package dev.fondaria.eodjob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.fondaria.ledger.LedgerLimits;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tuning the snapshot runs under, and the two values that fail startup rather than degrade. */
class SnapshotPropertiesTest {

  @Test
  @DisplayName("the local ceiling is the ledger's own, not a number of its own")
  void theCeilingMatchesTheLedger() {
    assertThat(SnapshotProperties.MAX_ACCOUNT_LOOKUP_BATCH)
        .as("held as a literal so the binding target has no ledger dependency; asserted here")
        .isEqualTo(LedgerLimits.MAX_LINKED_CHAIN_LENGTH);
  }

  @Test
  @DisplayName("absent values fall back to a working cadence")
  void absentValuesHaveWorkingDefaults() {
    SnapshotProperties properties = new SnapshotProperties(null, 0, 0, null, 0L);

    assertThat(properties.pollInterval()).isEqualTo(Duration.ofHours(1));
    assertThat(properties.holdingPageSize()).isPositive();
    assertThat(properties.accountLookupBatchSize())
        .isEqualTo(SnapshotProperties.MAX_ACCOUNT_LOOKUP_BATCH);
    assertThat(properties.keyPrefix()).isEqualTo("snapshots");
    assertThat(properties.maximumObjectBytes()).isPositive();
  }

  @Test
  @DisplayName("a batch larger than one ledger request fails startup rather than being truncated")
  void anOversizedBatchFailsStartup() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () ->
                new SnapshotProperties(
                    Duration.ofHours(1),
                    10_000,
                    SnapshotProperties.MAX_ACCOUNT_LOOKUP_BATCH + 1,
                    "snapshots",
                    1024L))
        .withMessageContaining("SNAPSHOT_ACCOUNT_LOOKUP_BATCH");
  }

  @Test
  @DisplayName("a key prefix with a stray slash fails startup rather than producing a doubled key")
  void aSlashedPrefixFailsStartup() {
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () -> new SnapshotProperties(Duration.ofHours(1), 10_000, 8_189, "snapshots/", 1024L))
        .withMessageContaining("SNAPSHOT_KEY_PREFIX");
  }

  @Test
  @DisplayName("a ceiling larger than a byte array fails startup rather than becoming an OOM")
  void anUnreachableCeilingFailsStartup() {
    // SnapshotTooLargeException tells an operator to raise this value, so raising it must not be
    // able to convert a bounded, self-explaining failure into an OutOfMemoryError partway through
    // a close. The file accumulates in a ByteArrayOutputStream, whose array cannot exceed
    // Integer.MAX_VALUE.
    assertThatExceptionOfType(ConfigurationException.class)
        .isThrownBy(
            () ->
                new SnapshotProperties(
                    Duration.ofHours(1),
                    10_000,
                    8_189,
                    "snapshots",
                    SnapshotProperties.MAX_OBJECT_BYTES + 1))
        .withMessageContaining("SNAPSHOT_MAX_OBJECT_BYTES");

    assertThat(
            new SnapshotProperties(
                    Duration.ofHours(1),
                    10_000,
                    8_189,
                    "snapshots",
                    SnapshotProperties.MAX_OBJECT_BYTES)
                .maximumObjectBytes())
        .as("exactly at the ceiling is allowed")
        .isEqualTo(SnapshotProperties.MAX_OBJECT_BYTES);
  }

  @Test
  @DisplayName("a prefix with surrounding whitespace is stripped, not rejected")
  void whitespaceIsStripped() {
    assertThat(new SnapshotProperties(null, 0, 0, "  eod/snapshots  ", 0L).keyPrefix())
        .isEqualTo("eod/snapshots");
  }
}
