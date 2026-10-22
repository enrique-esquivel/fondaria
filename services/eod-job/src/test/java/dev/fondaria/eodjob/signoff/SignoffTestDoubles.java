package dev.fondaria.eodjob.signoff;

import dev.fondaria.eodjob.custodian.CustodianReconciliationRepository;
import dev.fondaria.eodjob.custodian.CustodianReconciliationRun;
import dev.fondaria.eodjob.reconciliation.ReconciliationBreak;
import dev.fondaria.eodjob.reconciliation.ReconciliationRepository;
import dev.fondaria.eodjob.reconciliation.ReconciliationRun;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import dev.fondaria.eodjob.snapshot.SnapshotFundTotal;
import dev.fondaria.eodjob.snapshot.SnapshotRepository;
import dev.fondaria.eodjob.snapshot.SnapshotRun;
import dev.fondaria.eodjob.snapshot.SnapshotStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory stand-ins for everything {@link SignoffService} reads, so its tests need no database
 * and no broker. Each is the minimum surface the service actually calls; the rest throws.
 */
final class SignoffTestDoubles {

  private SignoffTestDoubles() {}

  static ReconciliationRun passedInternalRun(LocalDate businessDate, Instant at) {
    return new ReconciliationRun(
        UUID.randomUUID(),
        businessDate,
        RunStatus.PASSED,
        at,
        Optional.of(at.plusSeconds(30)),
        Optional.of(30_000L),
        3,
        1_000L,
        4,
        0);
  }

  static ReconciliationRun failedInternalRun(LocalDate businessDate, Instant at) {
    return new ReconciliationRun(
        UUID.randomUUID(),
        businessDate,
        RunStatus.FAILED,
        at,
        Optional.of(at.plusSeconds(30)),
        Optional.of(30_000L),
        3,
        1_000L,
        4,
        2);
  }

  static CustodianReconciliationRun passedCustodianRun(LocalDate businessDate, Instant at) {
    return new CustodianReconciliationRun(
        UUID.randomUUID(),
        businessDate,
        RunStatus.PASSED,
        at,
        Optional.of(at.plusSeconds(20)),
        Optional.of(20_000L),
        1,
        1,
        0,
        0,
        0);
  }

  static SnapshotRun completedSnapshot(LocalDate businessDate, Instant at) {
    return new SnapshotRun(
        UUID.randomUUID(),
        businessDate,
        SnapshotStatus.COMPLETED,
        at,
        Optional.of(at.plusSeconds(60)),
        Optional.of(60_000L),
        248_917L,
        3,
        Optional.of("snapshots/dt=" + businessDate + "/positions.ndjson.gz"),
        Optional.of("a".repeat(64)),
        Optional.of("snapshots/dt=" + businessDate + "/manifest.json"),
        Optional.of("b".repeat(64)),
        Optional.empty(),
        Optional.empty());
  }

  static final class Signoffs implements SignoffRepository {
    private final Map<LocalDate, CloseSignoff> byDate = new HashMap<>();

    @Override
    public boolean insertIfAbsent(CloseSignoff signoff) {
      return byDate.putIfAbsent(signoff.businessDate(), signoff) == null;
    }

    @Override
    public void markAnnounced(LocalDate businessDate, Instant announcedAt) {
      byDate.computeIfPresent(
          businessDate,
          (date, row) -> {
            if (row.isAnnounced()) {
              return row;
            }
            // GREATEST(announcedAt, signed_off_at), matching JdbcSignoffRepository.
            Instant clamped =
                announcedAt.isBefore(row.signedOffAt()) ? row.signedOffAt() : announcedAt;
            return new CloseSignoff(
                row.businessDate(),
                row.signedOffAt(),
                row.reconciliationRunId(),
                row.custodianRunId(),
                row.snapshotRunId(),
                row.snapshotPositionCount(),
                row.snapshotObjectKey(),
                row.snapshotManifestKey(),
                Optional.of(clamped));
          });
    }

    @Override
    public List<CloseSignoff> findUnannounced() {
      return byDate.values().stream()
          .filter(row -> !row.isAnnounced())
          .sorted(java.util.Comparator.comparing(CloseSignoff::businessDate))
          .toList();
    }

    @Override
    public Optional<CloseSignoff> find(LocalDate businessDate) {
      return Optional.ofNullable(byDate.get(businessDate));
    }

    void put(CloseSignoff row) {
      byDate.put(row.businessDate(), row);
    }
  }

  static final class Snapshots implements SnapshotRepository {
    private final Map<LocalDate, SnapshotRun> completed = new HashMap<>();

    Snapshots with(SnapshotRun run) {
      completed.put(run.businessDate(), run);
      return this;
    }

    @Override
    public Optional<SnapshotRun> latestCompletedFor(LocalDate businessDate) {
      return Optional.ofNullable(completed.get(businessDate));
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
    public Optional<SnapshotRun> latestCompletedBefore(LocalDate businessDate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SnapshotFundTotal> fundTotalsOf(UUID runId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SnapshotRun> completedRunsAscending() {
      return completed.values().stream()
          .sorted(Comparator.comparing(SnapshotRun::businessDate))
          .toList();
    }
  }

  static final class InternalRuns implements ReconciliationRepository {
    private final Map<LocalDate, ReconciliationRun> byDate = new HashMap<>();

    InternalRuns with(ReconciliationRun run) {
      byDate.put(run.businessDate(), run);
      return this;
    }

    @Override
    public Optional<ReconciliationRun> latestFor(LocalDate businessDate) {
      return Optional.ofNullable(byDate.get(businessDate));
    }

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void complete(ReconciliationRun run, List<ReconciliationBreak> breaks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<ReconciliationBreak> breaksOf(UUID runId) {
      return List.of();
    }
  }

  static final class CustodianRuns implements CustodianReconciliationRepository {
    private final Map<LocalDate, CustodianReconciliationRun> byDate = new HashMap<>();

    CustodianRuns with(CustodianReconciliationRun run) {
      byDate.put(run.businessDate(), run);
      return this;
    }

    @Override
    public Optional<CustodianReconciliationRun> latestFor(LocalDate businessDate) {
      return Optional.ofNullable(byDate.get(businessDate));
    }

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void complete(CustodianReconciliationRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis) {
      throw new UnsupportedOperationException();
    }
  }

  /** Records what it was asked to publish, and can be told to fail the next call. */
  static final class RecordingPublisher implements CloseSignedOffPublisher {
    private final List<CloseSignedOffEvent> published = new ArrayList<>();
    private int failuresRemaining = 0;

    void failNext(int count) {
      this.failuresRemaining = count;
    }

    List<CloseSignedOffEvent> published() {
      return List.copyOf(published);
    }

    CloseSignedOffEvent onlyPublished() {
      if (published.size() != 1) {
        throw new AssertionError("expected exactly one published event, got " + published.size());
      }
      return published.get(0);
    }

    @Override
    public void publish(CloseSignedOffEvent event) {
      if (failuresRemaining > 0) {
        failuresRemaining--;
        throw new PublishFailedException("test-induced publish failure", new RuntimeException());
      }
      published.add(event);
    }
  }
}
