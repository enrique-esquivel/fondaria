package dev.fondaria.eodjob.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code workflow.reconciliation_run} and {@code workflow.reconciliation_break} against a real
 * PostgreSQL ({@code V25}).
 *
 * <p>Every assertion is over ids this test created (AGENTS.md §4) — the fixture is shared and holds
 * whatever earlier runs recorded, so each test uses its own business date, far enough in the past
 * that no other suite files a run against it.
 */
@SpringBootTest
class JdbcReconciliationRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private ReconciliationRepository repository;
  @Autowired private JdbcClient jdbc;

  private FundId fund;
  private LocalDate businessDate;

  @BeforeEach
  void freshFixture() {
    fund = registerFund();
    // A date nothing else uses: 1980 is before the platform existed, and each test moves within it.
    businessDate =
        LocalDate.of(1980, 1, 1)
            .plusDays(java.util.concurrent.ThreadLocalRandom.current().nextInt(10_000));
  }

  @Test
  @DisplayName("a pass is RUNNING before it compares anything, and settles when it finishes")
  void aRunIsOpenedBeforeItIsSettled() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    ReconciliationRun opened = repository.latestFor(businessDate).orElseThrow();
    assertThat(opened.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(opened.completedAt()).isEmpty();
    assertThat(opened.opensTheGate())
        .as(
            "an unfinished pass is not a pass — the row exists so an operator can tell a crash"
                + " from a close that never started, not so the gate can let it through")
        .isFalse();

    repository.complete(passed(runId, startedAt), List.of());

    ReconciliationRun settled = repository.latestFor(businessDate).orElseThrow();
    assertThat(settled.status()).isEqualTo(RunStatus.PASSED);
    assertThat(settled.durationMillis()).contains(1_250L);
    assertThat(settled.opensTheGate()).isTrue();
  }

  @Test
  @DisplayName("breaks and the failing status are written together, and read back in order")
  void breaksAreRecordedWithTheirRun() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    List<ReconciliationBreak> raised =
        List.of(
            ReconciliationBreak.units(
                fund,
                1_001L,
                Derivation.LEDGER_PARTICIPANT_UNITS,
                900 * Units.SCALE,
                Derivation.PROJECTION_UNITS,
                899 * Units.SCALE),
            ReconciliationBreak.cash("340282366920938463463374607431768211455", -250L, 0L));
    repository.complete(failed(runId, startedAt, raised.size()), raised);

    List<ReconciliationBreak> readBack = repository.breaksOf(runId);
    assertThat(readBack).hasSize(2);
    assertThat(readBack.get(0).fundId()).contains(fund);
    assertThat(readBack.get(0).driftScaled()).isEqualTo(1 * Units.SCALE);
    assertThat(readBack.get(1).accountId())
        .as("a u128 account id survives the round trip through numeric(39,0) unchanged")
        .contains("340282366920938463463374607431768211455");
    assertThat(readBack.get(1).driftScaled())
        .as("a negative ledger balance against a projection that has seen nothing")
        .isEqualTo(-250L);
    assertThat(repository.latestFor(businessDate).orElseThrow().status())
        .isEqualTo(RunStatus.FAILED);
  }

  @Test
  @DisplayName("the gate reads the latest pass, not the friendliest one")
  void latestForOrdersBySettlement() {
    UUID morning = UUID.randomUUID();
    Instant morningAt = now();
    repository.open(morning, businessDate, morningAt);
    repository.complete(passed(morning, morningAt), List.of());

    UUID evening = UUID.randomUUID();
    Instant eveningAt = morningAt.plusSeconds(36_000);
    repository.open(evening, businessDate, eveningAt);
    repository.complete(failed(evening, eveningAt, 1), List.of(aUnitBreak()));

    assertThat(repository.latestFor(businessDate).orElseThrow().runId()).isEqualTo(evening);
  }

  @Test
  @DisplayName("a pass that could not finish is FAILED with nothing found — nothing was compared")
  void aFailedPassIsSettledWithNoBreaks() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    repository.fail(runId, startedAt.plusSeconds(2), 2_000L);

    ReconciliationRun settled = repository.latestFor(businessDate).orElseThrow();
    assertThat(settled.status()).isEqualTo(RunStatus.FAILED);
    assertThat(settled.breakCount()).isZero();
    assertThat(repository.breaksOf(runId)).isEmpty();
  }

  @Test
  @DisplayName(
      "a settled pass cannot be settled again — something else changed it, and that matters")
  void completingATwiceSettledRunIsRefused() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(passed(runId, startedAt), List.of());

    assertThatThrownBy(() -> repository.complete(passed(runId, startedAt), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer RUNNING");
  }

  @Test
  @DisplayName("a break is evidence: it cannot be edited or deleted, whatever it says")
  void breaksAreAppendOnly() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(failed(runId, startedAt, 1), List.of(aUnitBreak()));

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE workflow.reconciliation_break SET drift_scaled = 0"
                            + " WHERE run_id = :runId")
                    .param("runId", runId)
                    .update())
        .as(
            "V25 revokes UPDATE; the fixture role holds it only because it owns the schema, so"
                + " this asserts the constraint the runtime role actually runs under")
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName(
      "a slow pass that started first and found drift outranks a quick one that started second"
          + " and passed — the ordering is by settlement, in SQL as well as in the double")
  void latestForPrefersTheRunThatSettledLast() {
    // The concurrency case ReconciliationRepository#latestFor exists to get right. Asserted
    // against real SQL because the in-memory double could agree with a wrong ORDER BY forever.
    Instant slowStart = now();
    UUID slowAndFirst = UUID.randomUUID();
    UUID quickAndSecond = UUID.randomUUID();
    repository.open(slowAndFirst, businessDate, slowStart);
    repository.open(quickAndSecond, businessDate, slowStart.plusSeconds(60));

    repository.complete(
        settledAt(
            quickAndSecond,
            slowStart.plusSeconds(60),
            slowStart.plusSeconds(70),
            RunStatus.PASSED,
            0),
        List.of());
    assertThat(repository.latestFor(businessDate).orElseThrow().runId())
        .as("nothing else has settled yet, so the quick pass is the only evidence there is")
        .isEqualTo(quickAndSecond);

    repository.complete(
        settledAt(slowAndFirst, slowStart, slowStart.plusSeconds(600), RunStatus.FAILED, 1),
        List.of(aUnitBreak()));

    ReconciliationRun latest = repository.latestFor(businessDate).orElseThrow();
    assertThat(latest.runId())
        .as(
            "ordered by started_at the quick pass would win forever, and the drift the slow one"
                + " found would never reach the gate at all")
        .isEqualTo(slowAndFirst);
    assertThat(latest.opensTheGate()).isFalse();
  }

  @Test
  @DisplayName(
      "an unfinished run sorts behind every settled one, so it cannot block a date forever")
  void anUnfinishedRunSortsLast() {
    Instant abandonedAt = now();
    UUID abandoned = UUID.randomUUID();
    repository.open(abandoned, businessDate, abandonedAt);
    assertThat(repository.latestFor(businessDate).orElseThrow().runId())
        .as("nothing has settled, so the unfinished row is the latest evidence there is")
        .isEqualTo(abandoned);

    UUID rerun = UUID.randomUUID();
    Instant rerunAt = abandonedAt.plusSeconds(3_600);
    repository.open(rerun, businessDate, rerunAt);
    repository.complete(
        settledAt(rerun, rerunAt, rerunAt.plusSeconds(30), RunStatus.PASSED, 0), List.of());

    assertThat(repository.latestFor(businessDate).orElseThrow().runId())
        .as(
            "NULLS LAST: an abandoned row no later run could displace would need a manual UPDATE"
                + " to reopen the close")
        .isEqualTo(rerun);
  }

  // ---- fixture helpers ------------------------------------------------------------------

  private ReconciliationBreak aUnitBreak() {
    return ReconciliationBreak.units(
        fund,
        1_001L,
        Derivation.LEDGER_PARTICIPANT_UNITS,
        900 * Units.SCALE,
        Derivation.LEDGER_UNITS_IN_ISSUE,
        901 * Units.SCALE);
  }

  private ReconciliationRun passed(UUID runId, Instant startedAt) {
    return settled(runId, startedAt, RunStatus.PASSED, 0);
  }

  private ReconciliationRun failed(UUID runId, Instant startedAt, int breakCount) {
    return settled(runId, startedAt, RunStatus.FAILED, breakCount);
  }

  private ReconciliationRun settled(
      UUID runId, Instant startedAt, RunStatus status, int breakCount) {
    return new ReconciliationRun(
        runId,
        businessDate,
        status,
        startedAt,
        Optional.of(startedAt.plusMillis(1_250L)),
        Optional.of(1_250L),
        3,
        6L,
        11,
        breakCount);
  }

  private ReconciliationRun settledAt(
      UUID runId, Instant startedAt, Instant completedAt, RunStatus status, int breakCount) {
    return new ReconciliationRun(
        runId,
        businessDate,
        status,
        startedAt,
        Optional.of(completedAt),
        Optional.of(java.time.Duration.between(startedAt, completedAt).toMillis()),
        3,
        6L,
        11,
        breakCount);
  }

  /** Microsecond truncation: {@code timestamptz} keeps microseconds, and a comparison must too. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private FundId registerFund() {
    FundId fundId = new FundId(UUID.randomUUID());
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity
              (fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
               opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01',
                    100000000, now())
            """)
        .param("fundId", fundId.value())
        .param(
            "unitLedgerId",
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L))
        .param("unitScale", Units.SCALE)
        .update();
    return fundId;
  }
}
