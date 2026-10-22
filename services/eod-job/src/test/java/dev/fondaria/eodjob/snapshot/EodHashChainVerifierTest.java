package dev.fondaria.eodjob.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link EodHashChainVerifier} and {@code eod_hash_chain_valid}. */
class EodHashChainVerifierTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final TestSnapshotRepository repository = new TestSnapshotRepository();
  private final EodHashChainVerifier verifier = new EodHashChainVerifier(repository, registry);

  @Test
  @DisplayName("an empty snapshot repository reports valid chain (gauge = 1.0)")
  void emptyChainIsValid() {
    assertThat(verifier.verify()).isTrue();
    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "a single completed genesis snapshot with absent previous hash reports valid (gauge = 1.0)")
  void singleGenesisSnapshotIsValid() {
    repository.add(completedRun(LocalDate.of(2026, 9, 1), "manifest_hash_1", null));

    assertThat(verifier.verify()).isTrue();
    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "a genesis snapshot that improperly carries a previous manifest hash is invalid (gauge = 0.0)")
  void genesisWithPreviousHashIsInvalid() {
    repository.add(
        completedRun(LocalDate.of(2026, 9, 1), "manifest_hash_1", "spurious_previous_hash"));

    assertThat(verifier.verify()).isFalse();
    assertThat(gauge()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("with a genuine two-day snapshot chain in place, eod_hash_chain_valid is 1")
  void genuineTwoDaySnapshotChainIsValid() {
    String day1Hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String day2Hash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    repository.add(completedRun(LocalDate.of(2026, 9, 1), day1Hash, null));
    repository.add(completedRun(LocalDate.of(2026, 9, 2), day2Hash, day1Hash));

    assertThat(verifier.verify()).isTrue();
    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "corrupting one row's previous_manifest_sha256 in a two-day chain makes eod_hash_chain_valid 0")
  void corruptingPreviousHashMakesChainInvalid() {
    String day1Hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String day2Hash = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    String corruptedHash = "0000000000000000000000000000000000000000000000000000000000000000";

    repository.add(completedRun(LocalDate.of(2026, 9, 1), day1Hash, null));
    repository.add(completedRun(LocalDate.of(2026, 9, 2), day2Hash, corruptedHash));

    assertThat(verifier.verify()).isFalse();
    assertThat(gauge()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("missing manifest_sha256 on a completed row breaks the chain (gauge = 0.0)")
  void missingManifestHashIsInvalid() {
    repository.add(
        new SnapshotRun(
            UUID.randomUUID(),
            LocalDate.of(2026, 9, 1),
            SnapshotStatus.COMPLETED,
            Instant.parse("2026-09-01T12:00:00Z"),
            Optional.of(Instant.parse("2026-09-01T12:01:00Z")),
            Optional.of(60_000L),
            100L,
            1,
            Optional.of("k/pos.gz"),
            Optional.of("pos_hash"),
            Optional.of("k/manifest.json"),
            Optional.empty(), // Missing manifest hash!
            Optional.empty(),
            Optional.empty()));

    assertThat(verifier.verify()).isFalse();
    assertThat(gauge()).isEqualTo(0.0);
  }

  private double gauge() {
    return registry.get("eod_hash_chain_valid").gauge().value();
  }

  private static SnapshotRun completedRun(
      LocalDate date, String manifestSha256, String previousManifestSha256) {
    return new SnapshotRun(
        UUID.randomUUID(),
        date,
        SnapshotStatus.COMPLETED,
        Instant.parse("2026-09-01T12:00:00Z"),
        Optional.of(Instant.parse("2026-09-01T12:01:00Z")),
        Optional.of(60_000L),
        100L,
        1,
        Optional.of("k/pos.gz"),
        Optional.of("pos_hash"),
        Optional.of("k/manifest.json"),
        Optional.ofNullable(manifestSha256),
        Optional.ofNullable(previousManifestSha256),
        Optional.empty());
  }

  private static final class TestSnapshotRepository implements SnapshotRepository {
    private final List<SnapshotRun> runs = new ArrayList<>();

    void add(SnapshotRun run) {
      runs.add(run);
    }

    @Override
    public List<SnapshotRun> completedRunsAscending() {
      return runs.stream()
          .filter(SnapshotRun::isComplete)
          .sorted(Comparator.comparing(SnapshotRun::businessDate))
          .toList();
    }

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordIntent(UUID runId, String positionsObjectKey, String positionsSha256) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void complete(SnapshotRun run, List<SnapshotFundTotal> fundTotals) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis, String reason) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SnapshotRun> latestCompletedFor(LocalDate businessDate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<SnapshotRun> latestCompletedBefore(LocalDate businessDate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SnapshotFundTotal> fundTotalsOf(UUID runId) {
      throw new UnsupportedOperationException();
    }
  }
}
