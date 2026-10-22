package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fondaria.common.Units;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Task 6.6's first and third criteria: <b>contribution → ledger → CDC → projection → query</b>, and
 * no float anywhere in a response body.
 *
 * <p>One employer's file travels the whole platform once, in {@link #contributeAndConverge()}: it
 * is uploaded, validated, staged and enrolled by {@code contribution-api}; allotted into
 * TigerBeetle by {@code allotment-job}; streamed out by the stock {@code tigerbeetle amqp} CDC job;
 * carried to Redpanda by {@code amqp-redpanda-bridge}; projected by {@code projection-worker}; and
 * then read back over HTTP from {@code balance-api}. Nothing in that sentence is a double. See
 * {@link PlatformStack} for what is real and what is not.
 *
 * <p><b>The convergence is waited for, never slept through</b> ({@link Convergence}) — and the
 * condition waited on is the query surface itself, which is the thing the criterion is about: the
 * test does not peek at {@code projection.transfer} to decide the pipeline has finished and then
 * ask the API a question it already knows the answer to.
 *
 * <p>Every assertion is over ids this class created (AGENTS.md §4): its own organization, its own
 * fund on its own unit ledger, its own participant. Nothing counts rows in a table or messages on a
 * topic.
 */
@Tag("integration")
class ContributionToQueryEndToEndTest {

  /** MXN 3,000.00 employee and MXN 1,500.00 employer — the plan's own 1:2 matching ratio. */
  private static final long EMPLOYEE_CENTS = 300_000L;

  private static final long EMPLOYER_CENTS = 150_000L;

  private static PlatformStack stack;
  private static QueryApi query;
  private static Scenario scenario;

  @BeforeAll
  static void contributeAndConverge() {
    stack = PlatformStack.getInstance();
    query = new QueryApi(stack);
    scenario =
        Scenario.contribute(stack, EMPLOYEE_CENTS, EMPLOYER_CENTS, Scenario.TEN_PESOS_PER_UNIT);
    Scenario.refreshPrices(stack);

    Convergence.awaitScenarioIsQueryable(query, scenario);
  }

  @Test
  @DisplayName(
      "a contribution file becomes units in the ledger, movements in the projection, and the same"
          + " figures on every Query API endpoint")
  void theContributionReachesEveryQuerySurface() {
    long vested = scenario.vestedUnitsScaled();
    long unvested = scenario.unvestedUnitsScaled();
    assertThat(vested).as("MXN 3,000.00 at MXN 10.00 per unit").isEqualTo(300 * Units.SCALE);
    assertThat(unvested).as("MXN 1,500.00 at MXN 10.00 per unit").isEqualTo(150 * Units.SCALE);

    // ---- The balance: units from the ledger, price from the cache, no PostgreSQL in the path.
    QueryApi.Response balance = query.balance(scenario.participantId());
    assertThat(balance.status()).isEqualTo(200);
    assertThat(balance.asLong("investedValueCents")).isEqualTo(scenario.totalCents());

    JsonNode position = positionOf(balance.json().get("positions"), scenario.fundId().toString());
    assertThat(position.get("vestedUnitsScaled").asLong()).isEqualTo(vested);
    assertThat(position.get("unvestedUnitsScaled").asLong()).isEqualTo(unvested);
    assertThat(position.get("valueCents").asLong()).isEqualTo(scenario.totalCents());
    assertThat(position.get("nav").get("navScaled").asLong()).isEqualTo(scenario.navScaled());
    assertThat(position.get("nav").get("navVersionId").asLong())
        .as("every value records which NAV version produced it")
        .isPositive();
    assertThat(position.get("nav").get("stale").asBoolean()).isFalse();

    // ---- The movement history: this is the CDC-fed half, and the proof the chain actually ran.
    JsonNode items = query.movements(scenario.participantId()).json().get("items");
    assertThat(items).hasSize(2);
    for (JsonNode movement : items) {
      assertThat(movement.get("fundId").asText()).isEqualTo(scenario.fundId().toString());
      assertThat(movement.get("direction").asText())
          .as("units were issued into the participant's accounts")
          .isEqualTo("CREDIT");
      assertThat(movement.get("transferCode").asText()).isEqualTo("CONTRIBUTION_ALLOTMENT");
      assertThat(movement.get("transferId").asText()).isNotBlank();
    }
    assertThat(movementUnits(items, "VESTED")).isEqualTo(vested);
    assertThat(movementUnits(items, "UNVESTED")).isEqualTo(unvested);

    // ---- The balance as of the dealing date: the sparse holding series, times that day's price.
    QueryApi.Response asOf = query.balanceAsOf(scenario.participantId(), scenario.dealingDate());
    assertThat(asOf.status()).isEqualTo(200);
    assertThat(asOf.asLong("valueCents")).isEqualTo(scenario.totalCents());
    JsonNode asOfPosition = positionOf(asOf.json().get("positions"), scenario.fundId().toString());
    assertThat(asOfPosition.get("unitsScaled").asLong())
        .as("vested and unvested are summed into one figure per fund (ADR-0013)")
        .isEqualTo(vested + unvested);

    // ---- The employer's own view: the aggregate ties to the participant's balance.
    QueryApi.Response summary = query.summary(scenario.organizationId());
    assertThat(summary.status()).isEqualTo(200);
    assertThat(summary.json().get("totalParticipants").asInt()).isEqualTo(1);
    assertThat(summary.asLong("totalValueCents"))
        .as("one participant, so the organization's total is that participant's balance")
        .isEqualTo(scenario.totalCents());

    assertThat(summary.asLong("contributionsThisPeriodCents"))
        .as("the employer's wire, counted once — the leg debiting EMPLOYER_COLLECTIONS")
        .isEqualTo(scenario.contributionsInPeriod(query));
  }

  @Test
  @DisplayName("no endpoint returns a float in JSON, asserted on the raw response body")
  void noEndpointReturnsAFloat() {
    List<QueryApi.Response> responses =
        List.of(
            query.balance(scenario.participantId()),
            query.movements(scenario.participantId()),
            query.balanceAsOf(scenario.participantId(), scenario.dealingDate()),
            query.summary(scenario.organizationId()),
            // A problem detail is a response body too, and RFC 9457 carries a numeric status.
            query.get("/v1/participants/" + java.util.UUID.randomUUID() + "/balance"));

    for (QueryApi.Response response : responses) {
      assertThat(JsonNumbers.decimalLiteralsIn(response.body()))
          .as(
              "%s returned a non-integer JSON number; cash is cents, units and NAV are 1e-8"
                  + " integers, and no float may appear on the wire (AGENTS.md §4). Body: %s",
              response.path(), response.body())
          .isEmpty();
      assertThat(JsonNumbers.numbersIn(response.body()))
          .as("%s returned no numbers at all, so this proves nothing", response.path())
          .isNotEmpty();
    }
  }

  private static JsonNode positionOf(JsonNode positions, String fundId) {
    for (JsonNode position : positions) {
      if (fundId.equals(position.get("fundId").asText())) {
        return position;
      }
    }
    throw new AssertionError("no position for fund " + fundId + " in " + positions);
  }

  private static long movementUnits(JsonNode items, String holdingAccount) {
    for (JsonNode movement : items) {
      if (holdingAccount.equals(movement.get("holdingAccount").asText())) {
        return movement.get("unitsScaled").asLong();
      }
    }
    throw new AssertionError("no movement on the " + holdingAccount + " holding in " + items);
  }
}
