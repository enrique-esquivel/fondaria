package dev.fondaria.contribution.fund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.FundStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL and the schema behind {@link FundRepository}, against a real PostgreSQL — in particular
 * that a unit ledger id can never be claimed by two funds, and that {@code reference.fund} is
 * append-only by grant (ADR-0011), not by application discipline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcFundRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private FundRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("reference.fund grants SELECT and INSERT but never UPDATE or DELETE")
  void fundGrantsAreAppendOnly() {
    assertThat(privilege("reference.fund", "SELECT")).isTrue();
    assertThat(privilege("reference.fund", "INSERT")).isTrue();
    assertThat(privilege("reference.fund", "UPDATE")).isFalse();
    assertThat(privilege("reference.fund", "DELETE")).isFalse();
  }

  @Test
  @DisplayName("reference.fund_identity grants SELECT and INSERT but never UPDATE or DELETE")
  void fundIdentityGrantsAreAppendOnly() {
    assertThat(privilege("reference.fund_identity", "SELECT")).isTrue();
    assertThat(privilege("reference.fund_identity", "INSERT")).isTrue();
    assertThat(privilege("reference.fund_identity", "UPDATE")).isFalse();
    assertThat(privilege("reference.fund_identity", "DELETE")).isFalse();
  }

  @Test
  @DisplayName("two funds cannot claim the same unit ledger id")
  void twoFundsCannotClaimTheSameUnitLedgerId() {
    long sharedLedgerId = freshLedgerId();
    repository.publish(
        fund(FundId.of(UUID.randomUUID()), sharedLedgerId, LocalDate.of(2026, 1, 1), 1));

    assertThatThrownBy(
            () ->
                repository.publish(
                    fund(
                        FundId.of(UUID.randomUUID()), sharedLedgerId, LocalDate.of(2026, 1, 1), 1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("a fund resolved as-of a past date returns the version live at that date")
  void resolvesTheVersionInForceAsOfADate() {
    FundId fundId = FundId.of(UUID.randomUUID());
    long ledgerId = freshLedgerId();
    repository.publish(fund(fundId, ledgerId, LocalDate.of(2025, 1, 1), 1, FundStatus.ACTIVE));
    repository.publish(fund(fundId, ledgerId, LocalDate.of(2026, 1, 1), 2, FundStatus.SUSPENDED));

    Optional<Fund> resolvedEarly = repository.resolveAsOf(fundId, LocalDate.of(2025, 6, 1));
    Optional<Fund> resolvedLate = repository.resolveAsOf(fundId, LocalDate.of(2026, 6, 1));

    assertThat(resolvedEarly)
        .hasValueSatisfying(f -> assertThat(f.status()).isEqualTo(FundStatus.ACTIVE));
    assertThat(resolvedLate)
        .hasValueSatisfying(f -> assertThat(f.status()).isEqualTo(FundStatus.SUSPENDED));
    // The identity fields are carried unchanged across versions of the same fund.
    assertThat(resolvedEarly.orElseThrow().unitLedgerId()).isEqualTo(ledgerId);
    assertThat(resolvedLate.orElseThrow().unitLedgerId()).isEqualTo(ledgerId);
  }

  @Test
  @DisplayName("publishing a later version does not change an already-evaluated resolution")
  void laterPublicationDoesNotChangeAPastResolution() {
    FundId fundId = FundId.of(UUID.randomUUID());
    long ledgerId = freshLedgerId();
    repository.publish(fund(fundId, ledgerId, LocalDate.of(2025, 1, 1), 1));

    Optional<Fund> before = repository.resolveAsOf(fundId, LocalDate.of(2025, 6, 1));
    repository.publish(fund(fundId, ledgerId, LocalDate.of(2026, 1, 1), 2, FundStatus.CLOSED));
    Optional<Fund> after = repository.resolveAsOf(fundId, LocalDate.of(2025, 6, 1));

    assertThat(after).isEqualTo(before);
  }

  @Test
  @DisplayName("no fund resolves before any version has been published")
  void noVersionResolvesBeforeAnyIsPublished() {
    assertThat(repository.resolveAsOf(FundId.of(UUID.randomUUID()), LocalDate.of(2026, 1, 1)))
        .isEmpty();
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }

  private static long freshLedgerId() {
    // Fixtures are reused across local runs (docs/ENGINEERING-RULES.md §4), and the unit ledger id
    // is
    // globally unique, so a fixed constant would collide with a previous run. TigerBeetle's
    // real per-fund ledger ids start at 1001 (LedgerCodes); this test only needs freshness.
    return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
  }

  private static Fund fund(FundId fundId, long unitLedgerId, LocalDate effectiveFrom, int version) {
    return fund(fundId, unitLedgerId, effectiveFrom, version, FundStatus.ACTIVE);
  }

  private static Fund fund(
      FundId fundId, long unitLedgerId, LocalDate effectiveFrom, int version, FundStatus status) {
    return new Fund(
        fundId,
        effectiveFrom,
        version,
        "Caja de Ahorro Acme",
        "CAJA_DE_AHORRO",
        CurrencyCode.MXN,
        unitLedgerId,
        Units.SCALE,
        LocalDate.of(2025, 1, 1),
        Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
        status,
        Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }
}
