package dev.fondaria.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FundTest {

  private static Fund fund(long unitLedgerId, long unitScale, long openingNavScaled) {
    return new Fund(
        FundId.of(UUID.randomUUID()),
        LocalDate.of(2026, 1, 1),
        1,
        "Caja de Ahorro Acme",
        "CAJA_DE_AHORRO",
        CurrencyCode.MXN,
        unitLedgerId,
        unitScale,
        LocalDate.of(2026, 1, 1),
        Nav.of(openingNavScaled, CurrencyCode.MXN),
        FundStatus.ACTIVE,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void a_well_formed_fund_is_constructed() {
    Fund fund = fund(1001, Units.SCALE, 10 * Units.SCALE);

    assertThat(fund.unitLedgerId()).isEqualTo(1001L);
    assertThat(fund.status()).isEqualTo(FundStatus.ACTIVE);
  }

  @Test
  void a_non_positive_unit_ledger_id_is_rejected() {
    assertThatThrownBy(() -> fund(0, Units.SCALE, 10 * Units.SCALE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fund(-1, Units.SCALE, 10 * Units.SCALE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_unit_scale_other_than_the_fixed_point_scale_is_rejected() {
    assertThatThrownBy(() -> fund(1001, 100L, 10 * Units.SCALE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_non_positive_opening_nav_is_rejected() {
    assertThatThrownBy(() -> fund(1001, Units.SCALE, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void an_opening_nav_in_a_different_currency_than_the_base_currency_is_rejected() {
    assertThatThrownBy(
            () ->
                new Fund(
                    FundId.of(UUID.randomUUID()),
                    LocalDate.of(2026, 1, 1),
                    1,
                    "Caja de Ahorro Acme",
                    "CAJA_DE_AHORRO",
                    CurrencyCode.MXN,
                    1001,
                    Units.SCALE,
                    LocalDate.of(2026, 1, 1),
                    Nav.of(10 * Units.SCALE, CurrencyCode.USD),
                    FundStatus.ACTIVE,
                    Instant.parse("2026-01-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_blank_name_is_rejected() {
    assertThatThrownBy(
            () ->
                new Fund(
                    FundId.of(UUID.randomUUID()),
                    LocalDate.of(2026, 1, 1),
                    1,
                    "  ",
                    "CAJA_DE_AHORRO",
                    CurrencyCode.MXN,
                    1001,
                    Units.SCALE,
                    LocalDate.of(2026, 1, 1),
                    Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
                    FundStatus.ACTIVE,
                    Instant.parse("2026-01-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_version_below_one_is_rejected() {
    assertThatThrownBy(
            () ->
                new Fund(
                    FundId.of(UUID.randomUUID()),
                    LocalDate.of(2026, 1, 1),
                    0,
                    "Caja de Ahorro Acme",
                    "CAJA_DE_AHORRO",
                    CurrencyCode.MXN,
                    1001,
                    Units.SCALE,
                    LocalDate.of(2026, 1, 1),
                    Nav.of(10 * Units.SCALE, CurrencyCode.MXN),
                    FundStatus.ACTIVE,
                    Instant.parse("2026-01-01T00:00:00Z")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
