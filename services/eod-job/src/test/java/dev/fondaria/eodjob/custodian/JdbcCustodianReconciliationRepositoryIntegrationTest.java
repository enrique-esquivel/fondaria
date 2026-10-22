package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.eodjob.reconciliation.RunStatus;
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

/**
 * {@code workflow.custodian_reconciliation_run} against a real PostgreSQL ({@code V27}). Same
 * lifecycle and settlement-ordering shape as {@code JdbcReconciliationRepositoryIntegrationTest}
 * (Task 7.2), which is where the concurrency reasoning is spelled out in full.
 */
@SpringBootTest
class JdbcCustodianReconciliationRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private CustodianReconciliationRepository repository;

  private LocalDate businessDate;

  @BeforeEach
  void freshFixture() {
    businessDate = LocalDate.of(1980, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(10_000));
  }

  @Test
  @DisplayName("a run is RUNNING before it settles, and PASSED once complete")
  void aRunIsOpenedBeforeItIsSettled() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    CustodianReconciliationRun opened = repository.latestFor(businessDate).orElseThrow();
    assertThat(opened.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(opened.opensTheGate()).isFalse();

    repository.complete(passed(runId, startedAt));

    CustodianReconciliationRun settled = repository.latestFor(businessDate).orElseThrow();
    assertThat(settled.status()).isEqualTo(RunStatus.PASSED);
    assertThat(settled.opensTheGate()).isTrue();
  }

  @Test
  @DisplayName("a settled run cannot be settled again")
  void completingATwiceSettledRunIsRefused() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);
    repository.complete(passed(runId, startedAt));

    assertThatThrownBy(() -> repository.complete(passed(runId, startedAt)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer RUNNING");
  }

  @Test
  @DisplayName("a process that died leaves a FAILED row with nothing found")
  void aFailedPassIsSettledWithNoFindings() {
    UUID runId = UUID.randomUUID();
    Instant startedAt = now();
    repository.open(runId, businessDate, startedAt);

    repository.fail(runId, startedAt.plusSeconds(2), 2_000L);

    CustodianReconciliationRun settled = repository.latestFor(businessDate).orElseThrow();
    assertThat(settled.status()).isEqualTo(RunStatus.FAILED);
    assertThat(settled.realBreakCount()).isZero();
  }

  @Test
  @DisplayName("the gate reads the run that settled last, not the one that started last")
  void latestForOrdersBySettlement() {
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
            RunStatus.PASSED));
    assertThat(repository.latestFor(businessDate).orElseThrow().runId()).isEqualTo(quickAndSecond);

    repository.complete(
        settledAt(slowAndFirst, slowStart, slowStart.plusSeconds(600), RunStatus.FAILED));
    CustodianReconciliationRun latest = repository.latestFor(businessDate).orElseThrow();
    assertThat(latest.runId())
        .as("the slow pass settled last, so its finding is the newest evidence")
        .isEqualTo(slowAndFirst);
    assertThat(latest.opensTheGate()).isFalse();
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private CustodianReconciliationRun passed(UUID runId, Instant startedAt) {
    return settledAt(runId, startedAt, startedAt.plusMillis(500), RunStatus.PASSED);
  }

  private CustodianReconciliationRun settledAt(
      UUID runId, Instant startedAt, Instant completedAt, RunStatus status) {
    return new CustodianReconciliationRun(
        runId,
        businessDate,
        status,
        startedAt,
        Optional.of(completedAt),
        Optional.of(java.time.Duration.between(startedAt, completedAt).toMillis()),
        1,
        1,
        0,
        status == RunStatus.FAILED ? 1 : 0,
        0);
  }
}
