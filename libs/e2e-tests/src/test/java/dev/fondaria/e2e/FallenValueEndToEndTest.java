package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.Units;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Task 6.6's fourth criterion: a participant whose value has fallen below what was contributed is
 * displayed correctly, with a negative gain.
 *
 * <p>The whole platform runs, exactly as in {@link ContributionToQueryEndToEndTest} — the employer
 * wires MXN 4,500.00 and it becomes 450 units at MXN 10.00 — and then the fund's price falls to MXN
 * 6.00. Nothing about the participant's holding changes: they still hold 450 units, and 450 units
 * are now worth MXN 2,700.00. The gain is MXN −1,800.00.
 *
 * <p><b>There is no {@code gainCents} field on any Phase 6 endpoint, and this test does not invent
 * one.</b> A gain is a statement figure — {@code marketMovementCents} on {@code
 * ParticipantStatement} in {@code balance-api.yaml}, which Phase 7 builds and nothing serves yet.
 * What Phase 6 owes is that the figures a gain is computed <i>from</i> are correct and signed: a
 * value that fell, a contribution total that did not, and no clamp between them. So the gain here
 * is derived the way a caller derives it — value minus contributions — and asserted to be negative,
 * on the employer summary from two figures the API itself returned. When Phase 7 renders the field,
 * this is the arithmetic it must agree with.
 *
 * <p>A suite that only ever raises the price passes on a broken implementation
 * (docs/ENGINEERING-RULES.md §4). This is the falling half for the Query API, end to end.
 */
@Tag("integration")
class FallenValueEndToEndTest {

  /** MXN 4,500.00 wired: 300 units for the employee, 150 unvested for the employer. */
  private static final long EMPLOYEE_CENTS = 300_000L;

  private static final long EMPLOYER_CENTS = 150_000L;

  /** The price after the fall: MXN 6.00, down from MXN 10.00. */
  private static final long SIX_PESOS_PER_UNIT = 6 * Units.SCALE;

  private static PlatformStack stack;
  private static QueryApi query;
  private static Scenario scenario;
  private static LocalDate fallenOn;

  @BeforeAll
  static void contributeThenLetThePriceFall() {
    stack = PlatformStack.getInstance();
    query = new QueryApi(stack);
    scenario =
        Scenario.contribute(stack, EMPLOYEE_CENTS, EMPLOYER_CENTS, Scenario.TEN_PESOS_PER_UNIT);
    Scenario.refreshPrices(stack);

    Convergence.awaitScenarioIsQueryable(query, scenario);

    fallenOn =
        stack.allotmentJobBean(BusinessCalendar.class).nextBusinessDate(scenario.dealingDate());
    Scenario.strikeAndPublish(stack, scenario.fundId(), fallenOn, SIX_PESOS_PER_UNIT);
  }

  @Test
  @DisplayName(
      "a fallen NAV lowers the balance below what was contributed, leaving a negative gain and"
          + " the same units held")
  void aFallenPriceIsShownAsALoss() {
    long unitsHeld = scenario.vestedUnitsScaled() + scenario.unvestedUnitsScaled();
    long expectedValueCents = 270_000L; // 450 units x MXN 6.00

    QueryApi.Response balance = query.balance(scenario.participantId());
    assertThat(balance.status()).isEqualTo(200);
    assertThat(balance.asLong("investedValueCents")).isEqualTo(expectedValueCents);

    JsonNode position = positionOf(balance.json().get("positions"));
    assertThat(
            position.get("vestedUnitsScaled").asLong()
                + position.get("unvestedUnitsScaled").asLong())
        .as("a loss moves the price, never the units — the participant still holds what they held")
        .isEqualTo(unitsHeld);
    assertThat(position.get("nav").get("navScaled").asLong()).isEqualTo(SIX_PESOS_PER_UNIT);

    // The participant side subtracts the wire this test sent, because no participant-facing
    // endpoint reports a contribution total; theEmployerSeesTheLoss does the same subtraction
    // with two figures the API itself returned.
    long gainCents = balance.asLong("investedValueCents") - scenario.totalCents();
    assertThat(gainCents)
        .as("MXN 2,700.00 held against MXN 4,500.00 contributed, never clamped at what was paid in")
        .isEqualTo(-180_000L)
        .isNegative();
  }

  @Test
  @DisplayName("the fall is visible as of the day it happened, and does not rewrite the day before")
  void thePriceFallDoesNotRewriteHistory() {
    QueryApi.Response beforeTheFall =
        query.balanceAsOf(scenario.participantId(), scenario.dealingDate());
    assertThat(beforeTheFall.asLong("valueCents"))
        .as("the dealing date is still valued at the price struck for it")
        .isEqualTo(scenario.totalCents());

    QueryApi.Response afterTheFall = query.balanceAsOf(scenario.participantId(), fallenOn);
    assertThat(afterTheFall.asLong("valueCents")).isEqualTo(270_000L);
    assertThat(afterTheFall.asLong("valueCents")).isLessThan(beforeTheFall.asLong("valueCents"));
  }

  @Test
  @DisplayName("the employer's summary shows the loss too, against contributions that did not move")
  void theEmployerSeesTheLoss() {
    QueryApi.Response summary = query.summary(scenario.organizationId());
    assertThat(summary.status()).isEqualTo(200);
    assertThat(summary.asLong("totalValueCents")).isEqualTo(270_000L);

    // On the one boundary a month where the allotment falls outside the summary's window, the
    // employer's contributions for this period are genuinely zero and there is no gain to
    // compute against them. Scenario#contributionsInPeriod reads that window from the API.
    if (scenario.contributionsInPeriod(query) == 0L) {
      return;
    }

    assertThat(summary.asLong("contributionsThisPeriodCents")).isEqualTo(scenario.totalCents());
    assertThat(summary.asLong("totalValueCents") - summary.asLong("contributionsThisPeriodCents"))
        .as("the employer's own figures, subtracted: the plan is under water this month")
        .isEqualTo(-180_000L)
        .isNegative();
  }

  private static JsonNode positionOf(JsonNode positions) {
    for (JsonNode position : positions) {
      if (scenario.fundId().toString().equals(position.get("fundId").asText())) {
        return position;
      }
    }
    throw new AssertionError("no position for fund " + scenario.fundId() + " in " + positions);
  }
}
