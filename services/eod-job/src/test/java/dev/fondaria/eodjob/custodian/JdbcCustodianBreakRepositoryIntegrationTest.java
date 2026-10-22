package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import dev.fondaria.eodjob.support.ServiceIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code workflow.custodian_break} against a real PostgreSQL ({@code V27}): a mutable case, unlike
 * {@code workflow.reconciliation_break} — opened once, re-observed, assigned, investigated,
 * escalated on ageing, and resolved, never re-created for the same still-live condition.
 */
@SpringBootTest
class JdbcCustodianBreakRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private CustodianBreakRepository repository;
  @Autowired private JdbcClient jdbc;

  private UUID providerAccountId;
  private LocalDate businessDate;

  @BeforeEach
  void freshFixture() {
    FundId fund = registerFund();
    providerAccountId = registerProviderAccount(fund);
    businessDate = LocalDate.of(1980, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(10_000));
  }

  @Test
  @DisplayName("a break opens, is re-observed rather than duplicated, and resolves")
  void opensReObservesAndResolves() {
    CustodianBreak opened =
        repository.open(
            providerAccountId, BreakCategory.UNIT_BREAK, 10_000_000L, businessDate, now());
    assertThat(opened.status()).isEqualTo(BreakStatus.OPEN);
    assertThat(opened.driftScaled()).isEqualTo(10_000_000L);

    LocalDate nextDay = businessDate.plusDays(1);
    repository.confirmStillOpen(opened.breakId(), 9_000_000L, nextDay, now());

    CustodianBreak reobserved =
        repository.findOpen(providerAccountId, BreakCategory.UNIT_BREAK).orElseThrow();
    assertThat(reobserved.breakId())
        .as("re-observed, not duplicated into a second row")
        .isEqualTo(opened.breakId());
    assertThat(reobserved.driftScaled()).isEqualTo(9_000_000L);
    assertThat(reobserved.lastConfirmedBusinessDate()).isEqualTo(nextDay);
    assertThat(reobserved.firstDetectedBusinessDate())
        .as("age is measured from when it was first opened, not last confirmed")
        .isEqualTo(businessDate);

    repository.resolve(opened.breakId(), now(), "drift cleared");
    assertThat(repository.findOpen(providerAccountId, BreakCategory.UNIT_BREAK)).isEmpty();
  }

  @Test
  @DisplayName("the database refuses a second non-resolved break for the same account and category")
  void refusesASecondOpenBreakForTheSameAccountAndCategory() {
    jdbc.sql(
            """
            INSERT INTO workflow.custodian_break
              (break_id, provider_account_id, category, status, drift_scaled,
               first_detected_business_date, last_confirmed_business_date, opened_at, status_updated_at)
            VALUES (:id1, :providerAccountId, 'UNIT_BREAK', 'OPEN', 1, :businessDate, :businessDate, :now, :now)
            """)
        .param("id1", UUID.randomUUID())
        .param("providerAccountId", providerAccountId)
        .param("businessDate", businessDate)
        .param("now", java.sql.Timestamp.from(now()))
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO workflow.custodian_break
                          (break_id, provider_account_id, category, status, drift_scaled,
                           first_detected_business_date, last_confirmed_business_date, opened_at, status_updated_at)
                        VALUES (:id2, :providerAccountId, 'UNIT_BREAK', 'ASSIGNED', 2, :businessDate, :businessDate, :now, :now)
                        """)
                    .param("id2", UUID.randomUUID())
                    .param("providerAccountId", providerAccountId)
                    .param("businessDate", businessDate)
                    .param("now", java.sql.Timestamp.from(now()))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("assign and investigate move the status without touching the drift or the age")
  void assignAndInvestigateMoveStatus() {
    CustodianBreak opened =
        repository.open(providerAccountId, BreakCategory.CASH_BREAK, -500L, businessDate, now());

    repository.assign(opened.breakId(), "ops-team", now());
    CustodianBreak assigned =
        repository.findOpen(providerAccountId, BreakCategory.CASH_BREAK).orElseThrow();
    assertThat(assigned.status()).isEqualTo(BreakStatus.ASSIGNED);
    assertThat(assigned.assignedTo()).contains("ops-team");
    assertThat(assigned.driftScaled()).isEqualTo(-500L);

    repository.investigate(opened.breakId(), now());
    CustodianBreak investigating =
        repository.findOpen(providerAccountId, BreakCategory.CASH_BREAK).orElseThrow();
    assertThat(investigating.status()).isEqualTo(BreakStatus.INVESTIGATING);
    assertThat(investigating.assignedTo())
        .as("investigating does not clear who it was assigned to")
        .contains("ops-team");
  }

  @Test
  @DisplayName("a real break and a timing difference escalate on different thresholds")
  void escalatesOnDifferentThresholdsPerCategory() {
    CustodianBreak realBreak =
        repository.open(providerAccountId, BreakCategory.UNIT_BREAK, 1L, businessDate, now());
    CustodianBreak timing =
        repository.open(
            providerAccountId, BreakCategory.UNIT_TIMING_DIFFERENCE, 1L, businessDate, now());

    // escalateAged is an unscoped UPDATE across the whole table (AGENTS.md §4: fixtures are
    // reused, so the return count is not this test's to assert — a break another test in this
    // class left non-RESOLVED would inflate it). Only this test's own two breaks' statuses are
    // asserted.
    LocalDate fourDaysLater = businessDate.plusDays(4);
    repository.escalateAged(fourDaysLater, 3, 5, now());

    assertThat(
            repository.findOpen(providerAccountId, BreakCategory.UNIT_BREAK).orElseThrow().status())
        .as("aged past its threshold (3)")
        .isEqualTo(BreakStatus.ESCALATED);
    assertThat(
            repository
                .findOpen(providerAccountId, BreakCategory.UNIT_TIMING_DIFFERENCE)
                .orElseThrow()
                .status())
        .as("not aged past its longer threshold (5)")
        .isEqualTo(BreakStatus.OPEN);

    LocalDate sixDaysLater = businessDate.plusDays(6);
    repository.escalateAged(sixDaysLater, 3, 5, now());
    assertThat(
            repository
                .findOpen(providerAccountId, BreakCategory.UNIT_TIMING_DIFFERENCE)
                .orElseThrow()
                .status())
        .as("now aged past its own threshold (5)")
        .isEqualTo(BreakStatus.ESCALATED);
  }

  @Test
  @DisplayName(
      "hasOpenRealBreak turns true the moment a real break — not a timing difference — opens")
  void hasOpenRealBreakReflectsARealBreakOpening() {
    // hasOpenRealBreak is deliberately platform-wide (it is what the gate reads), so a shared,
    // never-reset fixture cannot support asserting it false (AGENTS.md §4: never assert a total
    // over a whole table). Only the true direction is provable: opening one more real break can
    // only keep the answer true or make it true, regardless of what other tests left behind.
    repository.open(
        providerAccountId, BreakCategory.CASH_TIMING_DIFFERENCE, 100L, businessDate, now());
    CustodianBreak real =
        repository.open(providerAccountId, BreakCategory.CASH_BREAK, 100L, businessDate, now());

    assertThat(repository.hasOpenRealBreak()).isTrue();

    repository.resolve(real.breakId(), now(), "cleared");
    assertThat(repository.findOpen(providerAccountId, BreakCategory.CASH_BREAK))
        .as("this test's own break is gone, whatever the platform-wide answer now is")
        .isEmpty();
  }

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
            VALUES (:fundId, :unitLedgerId, 'MXN', :unitScale, DATE '2020-01-01', 100000000, now())
            """)
        .param("fundId", fundId.value())
        .param("unitLedgerId", ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L))
        .param("unitScale", dev.fondaria.common.Units.SCALE)
        .update();
    return fundId;
  }

  private UUID registerProviderAccount(FundId fundId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO reference.provider_account
              (provider_account_id, fund_id, scope_type, organization_id, provider_ref,
               effective_from, version, published_at)
            VALUES (:id, :fundId, 'ALL_PARTICIPANTS', NULL, 'PROV-1', DATE '2020-01-01', 1, now())
            """)
        .param("id", id)
        .param("fundId", fundId.value())
        .update();
    return id;
  }
}
