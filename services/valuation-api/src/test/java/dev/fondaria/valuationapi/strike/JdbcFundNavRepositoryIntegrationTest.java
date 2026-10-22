package dev.fondaria.valuationapi.strike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuationapi.support.ValuationIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL and the schema behind {@link FundNavRepository}, against a real PostgreSQL — that {@code
 * reference.fund_nav} is append-only by grant and by trigger (ADR-0011), that a fund and date can
 * carry only one live strike, and that the strike is idempotent on that pair (Task 20.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcFundNavRepositoryIntegrationTest extends ValuationIntegrationTest {

  @Autowired private FundNavRepository repository;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;

  @Test
  @DisplayName("reference.fund_nav grants SELECT and INSERT but never UPDATE or DELETE")
  void fundNavGrantsAreAppendOnly() {
    assertThat(privilege("SELECT")).isTrue();
    assertThat(privilege("INSERT")).isTrue();
    assertThat(privilege("UPDATE")).isFalse();
    assertThat(privilege("DELETE")).isFalse();
  }

  @Test
  @DisplayName("a stored NAV row alone reproduces its own arithmetic exactly")
  void aStoredNavRowAloneReproducesItsOwnArithmeticExactly() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    Nav struckNav = Nav.of(1_050_000_000L, CurrencyCode.MXN);
    Units struckUnits = Units.of(1_000 * Units.SCALE);

    repository.strike(candidate(fundId, date, struckNav, struckUnits));
    FundNav stored = repository.findOriginal(fundId, date).orElseThrow();

    assertThat(stored.nav()).isEqualTo(struckNav);
    assertThat(stored.unitsInIssue()).isEqualTo(struckUnits);
    assertThat(stored.source()).isEqualTo("MODEL_B");
    assertThat(stored.supersedesId()).isNull();
    assertThat(stored.navVersionId()).isPositive();
  }

  @Test
  @DisplayName("striking the same valuation twice produces one row")
  void strikingTheSameValuationTwiceProducesOneRow() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav candidate =
        candidate(
            fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(500 * Units.SCALE));

    FundNav first = repository.strike(candidate);
    // A second attempt, even with different figures, must not overwrite the first: the
    // strike is idempotent on (fundId, valuationDate), not on the candidate's values.
    FundNav second =
        repository.strike(
            candidate(fundId, date, Nav.of(99 * Nav.SCALE, CurrencyCode.MXN), Units.of(1)));

    assertThat(second.navVersionId()).isEqualTo(first.navVersionId());
    assertThat(second.nav()).isEqualTo(first.nav());
    assertThat(rowCount(fundId, date)).isEqualTo(1);
  }

  @Test
  @DisplayName("a second live NAV for the same fund and date is rejected by the index")
  void aSecondLiveNavForTheSameFundAndDateIsRejectedByTheIndex() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    repository.strike(
        candidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1)));

    // Bypasses the repository's ON CONFLICT DO NOTHING to prove the constraint itself, not
    // just the repository's handling of it, is what makes a second live row impossible.
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO reference.fund_nav (
                            fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
                        VALUES (:fundId, :valuationDate, 1, 1, 'MODEL_B', :now)
                        """)
                    .param("fundId", fundId.value())
                    .param("valuationDate", date)
                    .param("now", Timestamp.from(clock.instant()))
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("UPDATE and DELETE both fail, attempted directly against the table")
  void updateAndDeleteBothFailFromTheAppRole() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav stored =
        repository.strike(
            candidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1)));

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE reference.fund_nav SET reason = 'x' WHERE nav_version_id = :id")
                    .param("id", stored.navVersionId())
                    .update())
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM reference.fund_nav WHERE nav_version_id = :id")
                    .param("id", stored.navVersionId())
                    .update())
        .isInstanceOf(DataAccessException.class);

    // Neither attempt took effect.
    assertThat(repository.findOriginal(fundId, date)).contains(stored);
  }

  @Test
  @DisplayName("a correction inserts a row and leaves the original readable and intact")
  void aCorrectionInsertsARowAndLeavesTheOriginalReadableAndIntact() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        repository.strike(
            candidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000)));

    FundNav corrected =
        repository.correct(
            correction(
                original,
                Nav.of(9 * Nav.SCALE, CurrencyCode.MXN),
                "provider restated the reported balance"));

    assertThat(corrected.navVersionId()).isNotEqualTo(original.navVersionId());
    assertThat(corrected.supersedesId()).isEqualTo(original.navVersionId());
    assertThat(corrected.nav()).isEqualTo(Nav.of(9 * Nav.SCALE, CurrencyCode.MXN));
    // The original is untouched — findOriginal identifies it by supersedes_id IS NULL,
    // which a correction never sets on the row it points at.
    assertThat(repository.findOriginal(fundId, date)).contains(original);
    assertThat(rowCount(fundId, date)).isEqualTo(2);
  }

  @Test
  @DisplayName("a correction with no reason is refused")
  void aCorrectionWithNoReasonIsRefused() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        repository.strike(
            candidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000)));

    assertThatThrownBy(
            () ->
                new FundNav(
                    0L,
                    fundId,
                    date,
                    Nav.of(9 * Nav.SCALE, CurrencyCode.MXN),
                    original.unitsInIssue(),
                    original.source(),
                    original.navVersionId(),
                    " ",
                    clock.instant()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("findLive resolves a mid-series date, not only the fund's latest")
  void findLiveResolvesAMidSeriesDate() {
    FundId fundId = registerFund();
    LocalDate first = LocalDate.of(2026, 3, 2);
    LocalDate second = LocalDate.of(2026, 3, 3);
    FundNav firstStrike =
        repository.strike(
            candidate(fundId, first, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000)));
    repository.strike(
        candidate(fundId, second, Nav.of(11 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000)));

    FundNav correctedFirst =
        repository.correct(
            correction(firstStrike, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "restated"));

    // findLive(first) must return the correction, not the superseded original — and must
    // not be confused by `second` being the fund's more recent valuation date.
    assertThat(repository.findLive(fundId, first)).contains(correctedFirst);
  }

  @Test
  @DisplayName("correcting a row that is not the live strike for its fund and date is refused")
  void correctingARowThatIsNotLiveIsRefused() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);
    FundNav original =
        repository.strike(
            candidate(fundId, date, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000)));
    repository.correct(correction(original, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "first fix"));

    // original is no longer live — something already supersedes it.
    assertThatThrownBy(
            () ->
                repository.correct(
                    correction(original, Nav.of(8 * Nav.SCALE, CurrencyCode.MXN), "second fix")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a nonexistent supersedesId is refused rather than silently inserted")
  void aNonexistentSupersedesIdIsRefused() {
    FundId fundId = registerFund();
    LocalDate date = LocalDate.of(2026, 3, 2);

    assertThatThrownBy(
            () ->
                repository.correct(
                    new FundNav(
                        0L,
                        fundId,
                        date,
                        Nav.of(9 * Nav.SCALE, CurrencyCode.MXN),
                        Units.of(1_000),
                        "MODEL_B",
                        999_999_999L,
                        "no such row",
                        clock.instant())))
        .isInstanceOf(IllegalStateException.class);
  }

  private FundNav correction(FundNav superseded, Nav correctedNav, String reason) {
    return new FundNav(
        0L,
        superseded.fundId(),
        superseded.valuationDate(),
        correctedNav,
        superseded.unitsInIssue(),
        superseded.source(),
        superseded.navVersionId(),
        reason,
        clock.instant());
  }

  private boolean privilege(String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", "reference.fund_nav")
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }

  private int rowCount(FundId fundId, LocalDate date) {
    return jdbc.sql(
            "SELECT count(*) FROM reference.fund_nav WHERE fund_id = :fundId AND valuation_date = :date")
        .param("fundId", fundId.value())
        .param("date", date)
        .query(Integer.class)
        .single();
  }

  private FundNav candidate(FundId fundId, LocalDate date, Nav nav, Units unitsInIssue) {
    return new FundNav(0L, fundId, date, nav, unitsInIssue, "MODEL_B", null, null, clock.instant());
  }

  private FundId registerFund() {
    FundId fundId = FundId.of(UUID.randomUUID());
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    jdbc.sql(
            """
            INSERT INTO reference.fund_identity (
                fund_id, unit_ledger_id, base_currency, unit_scale, inception_date,
                opening_nav_scaled, created_at)
            VALUES (:fundId, :unitLedgerId, 'MXN', 100000000, :inceptionDate, 1000000000, :now)
            """)
        .param("fundId", fundId.value())
        // Fresh per fund, and never colliding with another run's: the unit ledger id is
        // uniquely constrained across every fund that has ever existed (Task 20.1).
        .param("unitLedgerId", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000))
        .param("inceptionDate", LocalDate.of(2025, 1, 1))
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            INSERT INTO reference.fund (
                fund_id, effective_from, version, name, vehicle_type, status, published_at)
            VALUES (:fundId, :effectiveFrom, 1, :name, 'CAJA_DE_AHORRO', 'ACTIVE', :now)
            """)
        .param("fundId", fundId.value())
        .param("effectiveFrom", LocalDate.of(2025, 1, 1))
        .param("name", "Test fund " + fundId.value())
        .param("now", Timestamp.from(now))
        .update();
    return fundId;
  }
}
