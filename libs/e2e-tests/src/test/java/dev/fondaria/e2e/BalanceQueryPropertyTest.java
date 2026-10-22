package dev.fondaria.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TransferId;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Task 6.6's second criterion: after any random contribution sequence and a volatile NAV series
 * including losses, the query's answer equals an independently computed expectation.
 *
 * <h2>What "independently computed" means here</h2>
 *
 * <p>The expectation is recomputed in this class, from the inputs, in {@link BigInteger}: units are
 * bought at each day's price by the allotment rule ({@code cents × 1e14 ÷ nav}, floored — a
 * participant is never credited a unit that was not paid for), and the holding is valued at the
 * live price by the balance rule ({@code units × nav ÷ 1e14}, half-up, once per lot). Neither
 * {@code Money.value} nor {@code Units} arithmetic is called to produce it — an expectation that
 * called the production method would agree with it by construction and would prove nothing. What is
 * compared is two independent readings of the same specification.
 *
 * <h2>Why the NAV series must fall</h2>
 *
 * <p>A generated series that only rises leaves a whole class of defect invisible — a clamp, an
 * unsigned type, a "gain" that cannot be negative (docs/ENGINEERING-RULES.md §4). The generator
 * below therefore spans prices above and below every purchase price, and the final price is as
 * likely to be the lowest of the series as the highest.
 *
 * <h2>Why this one does not go through the CDC chain</h2>
 *
 * <p>The balance is {@code units × NAV} and touches no projection at all — units come from the
 * ledger and the price from memory (Task 6.2). Driving each of a property's tries through the CDC
 * pipeline would test the pipeline's convergence a hundred times over and this criterion's actual
 * question — does the arithmetic hold for any sequence — barely once. {@link
 * ContributionToQueryEndToEndTest} owns the chain; this owns the arithmetic, over a real
 * TigerBeetle and the real HTTP surface.
 */
@Tag("integration")
class BalanceQueryPropertyTest {

  /** Prices from MXN 0.50 to MXN 40.00 per unit, 1e-8 scaled. Purchases happen across the range. */
  private static final long LOWEST_NAV = Units.SCALE / 2;

  private static final long HIGHEST_NAV = 40 * Units.SCALE;

  @Property(tries = 12)
  void aBalanceEqualsAnIndependentlyComputedExpectation(
      @ForAll @Size(min = 1, max = 6)
          List<@LongRange(min = 1_00L, max = 5_000_00L) Long> contributions,
      @ForAll @Size(min = 2, max = 6)
          List<@LongRange(min = LOWEST_NAV, max = HIGHEST_NAV) Long> navSeries,
      @ForAll @IntRange(min = 0, max = 63) int vestingPattern,
      // Half the tries end below every price the units were bought at. Left to chance, whether
      // the criterion's "including losses" half is exercised at all would vary run to run.
      @ForAll boolean endsBelowEveryPurchase) {

    PlatformStack stack = PlatformStack.getInstance();
    QueryApi query = new QueryApi(stack);
    JdbcClient jdbc = stack.balanceApiBean(JdbcClient.class);
    LedgerPort ledger = stack.balanceApiBean(LedgerPort.class);
    AccountIdDeriver accountIds = stack.balanceApiBean(AccountIdDeriver.class);

    // Registered through the services that own these records, not by hand-written INSERTs: a
    // fund published any other way can carry rows production could never produce (a tax
    // identifier that fails validation, a missing fund_identity), and a hardcoded column list
    // breaks silently on the next NOT NULL migration.
    OrganizationId organizationId = Scenario.registerOrganization(stack);
    String internalRef = "EMP-" + UUID.randomUUID();
    ParticipantId participantId = enrol(jdbc, organizationId, internalRef);
    long unitLedgerId = ThreadLocalRandom.current().nextLong(1_001L, 2_000_000_000L);
    FundId fundId = Scenario.registerFund(stack, unitLedgerId);

    String scope = organizationId.scope() + ":" + internalRef + ":" + fundId;
    AccountId unitsInIssue =
        accountIds.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    AccountId vestedAccount =
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope);
    AccountId unvestedAccount =
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope);
    ledger.createAccount(
        new CreateAccountCommand(
            unitsInIssue, unitLedgerId, LedgerCodes.AccountCode.UNITS_IN_ISSUE));
    ledger.createAccount(
        new CreateAccountCommand(
            vestedAccount, unitLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED));
    ledger.createAccount(
        new CreateAccountCommand(
            unvestedAccount, unitLedgerId, LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED));

    // Each contribution is bought at its own day's price, and the series is walked in order so a
    // later purchase can be dearer or cheaper than an earlier one.
    long expectedVestedUnits = 0;
    long expectedUnvestedUnits = 0;
    LocalDate day = LocalDate.of(2026, 1, 5);
    for (int i = 0; i < contributions.size(); i++) {
      long cents = contributions.get(i);
      long navOfTheDay = navSeries.get(i % navSeries.size());
      long units = unitsBought(cents, navOfTheDay);
      boolean vested = ((vestingPattern >> (i % 6)) & 1) == 1;
      issue(ledger, unitsInIssue, vested ? vestedAccount : unvestedAccount, unitLedgerId, units);
      if (vested) {
        expectedVestedUnits += units;
      } else {
        expectedUnvestedUnits += units;
      }
      strike(jdbc, fundId, day, navOfTheDay);
      day = day.plusDays(1);
    }

    long livePrice =
        endsBelowEveryPurchase
            ? navSeries.stream().mapToLong(Long::longValue).min().orElseThrow() / 2
            : navSeries.get(navSeries.size() - 1);
    strike(jdbc, fundId, day, livePrice);
    Scenario.refreshPrices(stack);

    long expectedValueCents =
        valueOf(expectedVestedUnits, livePrice) + valueOf(expectedUnvestedUnits, livePrice);

    QueryApi.Response balance = query.balance(participantId);
    assertThat(balance.status()).isEqualTo(200);

    JsonNode position = positionOf(balance.json().get("positions"), fundId);
    assertThat(position.get("vestedUnitsScaled").asLong()).isEqualTo(expectedVestedUnits);
    assertThat(position.get("unvestedUnitsScaled").asLong()).isEqualTo(expectedUnvestedUnits);
    assertThat(position.get("nav").get("navScaled").asLong()).isEqualTo(livePrice);
    assertThat(position.get("valueCents").asLong()).isEqualTo(expectedValueCents);
    assertThat(balance.asLong("investedValueCents"))
        .as("the headline figure is the sum of the rendered lots, to the centavo")
        .isEqualTo(expectedValueCents);
  }

  /** The allotment rule, recomputed: {@code cents × 1e14 ÷ nav}, floored. */
  private static long unitsBought(long cents, long navScaled) {
    return BigInteger.valueOf(cents)
        .multiply(BigInteger.valueOf(100_000_000_000_000L))
        .divide(BigInteger.valueOf(navScaled))
        .longValueExact();
  }

  /** The valuation rule, recomputed: {@code units × nav ÷ 1e14}, half-up, once per lot. */
  private static long valueOf(long unitsScaled, long navScaled) {
    BigInteger divisor = BigInteger.valueOf(100_000_000_000_000L);
    BigInteger[] quotientAndRemainder =
        BigInteger.valueOf(unitsScaled)
            .multiply(BigInteger.valueOf(navScaled))
            .divideAndRemainder(divisor);
    BigInteger quotient = quotientAndRemainder[0];
    if (quotientAndRemainder[1].multiply(BigInteger.TWO).compareTo(divisor) >= 0) {
      quotient = quotient.add(BigInteger.ONE);
    }
    return quotient.longValueExact();
  }

  private static void issue(
      LedgerPort ledger, AccountId from, AccountId to, long unitLedgerId, long unitsScaled) {
    ledger.createTransfer(
        CreateTransferCommand.Unit.single(
            TransferId.of(
                UUID.randomUUID().getMostSignificantBits(),
                UUID.randomUUID().getLeastSignificantBits()),
            from,
            to,
            unitLedgerId,
            LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT,
            Units.of(unitsScaled)));
  }

  private static void strike(JdbcClient jdbc, FundId fundId, LocalDate day, long navScaled) {
    jdbc.sql(
            """
            INSERT INTO reference.fund_nav (
                fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
            VALUES (:fundId, :valuationDate, :navScaled, 0, 'MODEL_B', :now)
            ON CONFLICT DO NOTHING
            """)
        .param("fundId", fundId.value())
        .param("valuationDate", day)
        .param("navScaled", navScaled)
        .param("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
        .update();
  }

  private static ParticipantId enrol(
      JdbcClient jdbc, OrganizationId organizationId, String internalRef) {
    Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    jdbc.sql(
            """
            INSERT INTO master.participant (
                organization_id, participant_internal_ref, plan_id, participant_payroll_ref,
                participant_status, employment_status, employment_start_date, enrolment_date,
                created_at, updated_at)
            VALUES (:organizationId, :internalRef, 'PLAN-A', :payrollRef,
                    'ACTIVE', 'ACTIVE', :employmentStart, :enrolment, :now, :now)
            """)
        .param("organizationId", organizationId.value())
        .param("internalRef", internalRef)
        .param("payrollRef", "PR-" + UUID.randomUUID())
        .param("employmentStart", LocalDate.of(2024, 1, 15))
        .param("enrolment", LocalDate.of(2024, 2, 1))
        .param("now", now)
        .update();
    return ParticipantId.of(
        jdbc.sql(
                """
                SELECT participant_id FROM master.participant
                 WHERE organization_id = :organizationId
                   AND participant_internal_ref = :internalRef
                """)
            .param("organizationId", organizationId.value())
            .param("internalRef", internalRef)
            .query(UUID.class)
            .single());
  }

  private static JsonNode positionOf(JsonNode positions, FundId fundId) {
    for (JsonNode position : positions) {
      if (fundId.toString().equals(position.get("fundId").asText())) {
        return position;
      }
    }
    throw new AssertionError("no position for fund " + fundId + " in " + positions);
  }
}
