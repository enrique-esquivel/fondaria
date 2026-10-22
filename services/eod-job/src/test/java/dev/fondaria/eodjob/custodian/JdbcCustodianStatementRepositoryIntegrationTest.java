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
 * {@code workflow.custodian_statement} against a real PostgreSQL ({@code V27}): one statement per
 * {@code (provider_account_id, business_date)}, enforced by the database, not only by {@link
 * CustodianStatementIngestionService}.
 */
@SpringBootTest
class JdbcCustodianStatementRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private CustodianStatementRepository repository;
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
  @DisplayName("a statement round-trips every figure, including a signed pending-trade amount")
  void roundTripsEveryFigure() {
    CustodianStatementSubmission submission =
        new CustodianStatementSubmission(
            providerAccountId, businessDate, 1_000L, 500_00L, -300L, -150L, "file.csv", "sha-1");

    CustodianStatement stored = repository.insert(UUID.randomUUID(), submission, now());

    CustodianStatement found = repository.findFor(providerAccountId, businessDate).orElseThrow();
    assertThat(found.statementId()).isEqualTo(stored.statementId());
    assertThat(found.reportedUnitsScaled()).isEqualTo(1_000L);
    assertThat(found.reportedCashCents()).isEqualTo(500_00L);
    assertThat(found.pendingTradeUnitsScaled())
        .as("signed: a trade in flight can move the position either direction")
        .isEqualTo(-300L);
    assertThat(found.pendingTradeCashCents()).isEqualTo(-150L);
  }

  @Test
  @DisplayName("the database refuses a second statement for the same account and date")
  void refusesASecondStatementForTheSameDate() {
    repository.insert(
        UUID.randomUUID(),
        new CustodianStatementSubmission(
            providerAccountId, businessDate, 1_000L, 500_00L, 0L, 0L, "file-1.csv", "sha-1"),
        now());

    assertThatThrownBy(
            () ->
                repository.insert(
                    UUID.randomUUID(),
                    new CustodianStatementSubmission(
                        providerAccountId,
                        businessDate,
                        999L,
                        499_00L,
                        0L,
                        0L,
                        "file-2.csv",
                        "sha-2"),
                    now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("no statement for a date that was never submitted")
  void noStatementForAnUnsubmittedDate() {
    assertThat(repository.findFor(providerAccountId, businessDate)).isEmpty();
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
