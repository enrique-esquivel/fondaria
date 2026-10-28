package dev.fondaria.eodjob.signoff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code workflow.close_signoff} against a real PostgreSQL ({@code V29}).
 *
 * <p>Each test uses its own business date, far in the past, so assertions are over rows this test
 * created (docs/ENGINEERING-RULES.md §4) — the fixture is shared.
 */
@SpringBootTest
class JdbcSignoffRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private SignoffRepository repository;
  @Autowired private JdbcClient jdbc;

  private CloseRunFixtures runs;
  private LocalDate businessDate;
  private Instant at;

  @BeforeEach
  void freshFixture() {
    runs = new CloseRunFixtures(jdbc);
    // A wide range so the reused fixture does not accumulate collisions on
    // snapshot_run_one_of_record_per_date — the same reasoning TASK-LOG step 44 records for the
    // snapshot suite. Kept under year 9999: LocalDate renders a five-digit year as "+867907-..."
    // which timestamptz rejects.
    businessDate =
        LocalDate.of(1000, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(3_000_000));
    at = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * One full set of upstream runs, reused across a test — a date has one COMPLETED snapshot only.
   */
  private CloseSignoff signoffFor(LocalDate date) {
    return signoffFor(
        date,
        runs.passedInternalRun(date, at),
        runs.passedCustodianRun(date, at),
        runs.completedSnapshotRun(date, at, 4242L));
  }

  private CloseSignoff signoffFor(LocalDate date, UUID internal, UUID custodian, UUID snapshot) {
    return new CloseSignoff(
        date,
        at,
        internal,
        custodian,
        snapshot,
        4242L,
        "snapshots/dt=" + date + "/positions.ndjson.gz",
        "snapshots/dt=" + date + "/manifest.json",
        Optional.empty());
  }

  @Test
  @DisplayName("a sign-off is written and read back, announced_at null until it is marked")
  void writtenAndReadBack() {
    CloseSignoff row = signoffFor(businessDate);

    assertThat(repository.insertIfAbsent(row)).isTrue();

    CloseSignoff read = repository.find(businessDate).orElseThrow();
    assertThat(read.reconciliationRunId()).isEqualTo(row.reconciliationRunId());
    assertThat(read.custodianRunId()).isEqualTo(row.custodianRunId());
    assertThat(read.snapshotRunId()).isEqualTo(row.snapshotRunId());
    assertThat(read.snapshotPositionCount()).isEqualTo(4242L);
    assertThat(read.snapshotObjectKey()).isEqualTo(row.snapshotObjectKey());
    assertThat(read.isAnnounced()).isFalse();

    Instant announcedAt = at.plusSeconds(5);
    repository.markAnnounced(businessDate, announcedAt);
    assertThat(repository.find(businessDate).orElseThrow().announcedAt()).contains(announcedAt);
  }

  @Test
  @DisplayName("the latest signed-off date is at least the one this test signed off")
  void latestSignedOffDateIsTheMaximum() {
    // The fixture is shared, so other tests' later dates may outrank this one; only the lower
    // bound is this test's to assert (docs/ENGINEERING-RULES.md §4).
    repository.insertIfAbsent(signoffFor(businessDate));

    assertThat(repository.latestSignedOffDate())
        .hasValueSatisfying(latest -> assertThat(latest).isAfterOrEqualTo(businessDate));
  }

  @Test
  @DisplayName("a second insert for the same date is a no-op, not an error")
  void insertIsIdempotentOnBusinessDate() {
    UUID snapshot = runs.completedSnapshotRun(businessDate, at, 4242L);
    CloseSignoff first =
        signoffFor(
            businessDate,
            runs.passedInternalRun(businessDate, at),
            runs.passedCustodianRun(businessDate, at),
            snapshot);
    // A genuinely different candidate — a later re-run of internal reconciliation — but the same
    // date, so the second insert must still be refused.
    CloseSignoff second =
        signoffFor(
            businessDate,
            runs.passedInternalRun(businessDate, at.plusSeconds(60)),
            runs.passedCustodianRun(businessDate, at.plusSeconds(60)),
            snapshot);

    assertThat(repository.insertIfAbsent(first)).isTrue();
    assertThat(repository.insertIfAbsent(second)).isFalse();

    assertThat(repository.find(businessDate).orElseThrow().reconciliationRunId())
        .as("the first write stands; the second changed nothing")
        .isEqualTo(first.reconciliationRunId());
  }

  @Test
  @DisplayName("marking announced twice does not move the timestamp")
  void markAnnouncedIsIdempotent() {
    repository.insertIfAbsent(signoffFor(businessDate));

    Instant firstMark = at.plusSeconds(5);
    repository.markAnnounced(businessDate, firstMark);
    repository.markAnnounced(businessDate, at.plusSeconds(60));

    assertThat(repository.find(businessDate).orElseThrow().announcedAt()).contains(firstMark);
  }

  @Test
  @DisplayName("an announced instant before the sign-off is clamped up, never stored behind it")
  void markAnnouncedClampsToSignedOffAt() {
    repository.insertIfAbsent(signoffFor(businessDate)); // signed_off_at = at

    // Host clock skew between two instances: the announcing instance's clock is behind.
    repository.markAnnounced(businessDate, at.minusSeconds(30));

    assertThat(repository.find(businessDate).orElseThrow().announcedAt())
        .as("GREATEST(announcedAt, signed_off_at) — the invariant holds without a constraint")
        .contains(at);
  }

  @Test
  @DisplayName(
      "findUnannounced returns the pending rows but not the announced one, oldest date first")
  void findUnannouncedReturnsPendingOldestFirst() {
    // The fixture is shared and this query is table-wide by design, so assert over this test's own
    // three dates only (docs/ENGINEERING-RULES.md §4) — never over the whole result.
    LocalDate older = businessDate.minusDays(3);
    LocalDate newer = businessDate.plusDays(3);
    repository.insertIfAbsent(signoffFor(newer));
    repository.insertIfAbsent(signoffFor(older));
    repository.insertIfAbsent(signoffFor(businessDate));
    repository.markAnnounced(businessDate, at.plusSeconds(1)); // this one is done

    java.util.Set<LocalDate> mine = java.util.Set.of(older, newer, businessDate);
    java.util.List<LocalDate> pendingOfMine =
        repository.findUnannounced().stream()
            .map(CloseSignoff::businessDate)
            .filter(mine::contains)
            .toList();

    assertThat(pendingOfMine)
        .as("the announced date is gone; the two pending ones are in business-date order")
        .containsExactly(older, newer);
  }
}
