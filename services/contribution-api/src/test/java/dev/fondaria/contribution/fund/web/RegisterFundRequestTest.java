package dev.fondaria.contribution.fund.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Field-level parsing and validation for {@link RegisterFundRequest}. */
class RegisterFundRequestTest {

  @Test
  @DisplayName("a well-formed request parses every field")
  void parsesEveryField() {
    UUID id = UUID.randomUUID();
    RegisterFundRequest request =
        new RegisterFundRequest(
            id.toString(),
            "2026-01-01",
            1,
            " Caja de Ahorro Acme ",
            " CAJA_DE_AHORRO ",
            "mxn",
            Units.SCALE,
            "2020-01-01",
            10 * Units.SCALE);

    assertThat(request.parsedFundId()).contains(FundId.of(id));
    assertThat(request.parsedEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(request.parsedVersion()).isEqualTo(1);
    assertThat(request.parsedName()).isEqualTo("Caja de Ahorro Acme");
    assertThat(request.parsedVehicleType()).isEqualTo("CAJA_DE_AHORRO");
    assertThat(request.parsedCurrency()).isEqualTo(CurrencyCode.MXN);
    assertThat(request.parsedUnitScale()).isEqualTo(Units.SCALE);
    assertThat(request.parsedLaunchDate()).isEqualTo(LocalDate.of(2020, 1, 1));
    assertThat(request.parsedLaunchNavScaled()).isEqualTo(10 * Units.SCALE);
  }

  @Test
  @DisplayName("an absent fundId parses to empty, not an error")
  void absentFundIdIsEmpty() {
    assertThat(request(null).parsedFundId()).isEmpty();
  }

  @Test
  @DisplayName("a malformed fundId is rejected without a stack trace leaking to the caller")
  void malformedFundIdIsRejected() {
    assertThatThrownBy(() -> request("not-a-uuid").parsedFundId())
        .isInstanceOf(InvalidFundRequestException.class);
  }

  @Test
  @DisplayName("a currency other than MXN is rejected (v1 is MXN-only)")
  void nonMxnCurrencyIsRejected() {
    RegisterFundRequest request =
        new RegisterFundRequest(
            null,
            "2026-01-01",
            1,
            "Fund",
            "CAJA_DE_AHORRO",
            "USD",
            Units.SCALE,
            "2020-01-01",
            10 * Units.SCALE);

    assertThatThrownBy(request::parsedCurrency)
        .isInstanceOf(InvalidFundRequestException.class)
        .hasMessageContaining("MXN");
  }

  @Test
  @DisplayName("a unit scale other than 1e8 is not caught here, but the caller must supply one")
  void unitScaleMustBeSupplied() {
    RegisterFundRequest request =
        new RegisterFundRequest(
            null,
            "2026-01-01",
            1,
            "Fund",
            "CAJA_DE_AHORRO",
            "MXN",
            null,
            "2020-01-01",
            10 * Units.SCALE);

    assertThatThrownBy(request::parsedUnitScale).isInstanceOf(InvalidFundRequestException.class);
  }

  @Test
  @DisplayName("an unparseable effectiveFrom is rejected, naming the field")
  void unparseableDateIsRejected() {
    RegisterFundRequest request =
        new RegisterFundRequest(
            null,
            "not-a-date",
            1,
            "Fund",
            "CAJA_DE_AHORRO",
            "MXN",
            Units.SCALE,
            "2020-01-01",
            10 * Units.SCALE);

    assertThatThrownBy(request::parsedEffectiveFrom)
        .isInstanceOf(InvalidFundRequestException.class)
        .hasMessageContaining("effectiveFrom");
  }

  private static RegisterFundRequest request(String fundId) {
    return new RegisterFundRequest(
        fundId,
        "2026-01-01",
        1,
        "Fund",
        "CAJA_DE_AHORRO",
        "MXN",
        Units.SCALE,
        "2020-01-01",
        10 * Units.SCALE);
  }
}
