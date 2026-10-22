package dev.fondaria.balanceapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP surface of {@code GET /v1/participants/{participantId}/balance}: status codes, problem
 * details, and the shape of the JSON.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest} — no database, no ledger, no context.
 * What is under test is the controller and its {@link BalanceExceptionHandler}, and booting a
 * context to reach them would make a fast unit test a slow integration one.
 */
class BalanceControllerTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void servesTheBalanceWithEveryFigureAsAnIntegerAndTheNavVersionBehindIt() throws Exception {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeLedgerPort ledger = new FakeLedgerPort();
    String scope = ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fund;
    ledger.withUnitAccount(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        1001L,
        100_00000000L);

    String body =
        mockMvc(resolving(), ledger, new FakeNavCache().with(fund, 12_50000000L, 4242))
            .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
            .andExpect(status().isOk())
            // 100 units at 12.50 => 1,250.00 pesos => 125,000 cents.
            .andExpect(jsonPath("$.investedValueCents").value(125_000))
            .andExpect(jsonPath("$.stale").value(false))
            .andExpect(jsonPath("$.positions[0].vestedUnitsScaled").value(10_000_000_000L))
            .andExpect(jsonPath("$.positions[0].unvestedUnitsScaled").value(0))
            .andExpect(jsonPath("$.positions[0].nav.navVersionId").value(4242))
            .andExpect(jsonPath("$.positions[0].nav.navScaled").value(1_250_000_000L))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoDecimalNumbers(body);
  }

  /**
   * "No endpoint returns a float in JSON", asserted on the raw body rather than on a parsed value —
   * a decimal that Jackson rendered would still deserialise into a number and pass a typed check.
   */
  @Test
  void neverRendersAnAmountAsADecimalOrAString() throws Exception {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeLedgerPort ledger = new FakeLedgerPort();
    String scope = ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fund;
    // A holding whose value does not divide evenly: 3 units at 33.333333 pesos.
    ledger.withUnitAccount(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        1001L,
        3_00000000L);

    String body =
        mockMvc(resolving(), ledger, new FakeNavCache().with(fund, 33_33333333L, 1))
            .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoDecimalNumbers(body);
    assertThat(body).doesNotContain("\"investedValueCents\":\"");
  }

  @Test
  void anUnknownParticipantIsFourOhFourWithoutEchoingTheId() throws Exception {
    String body =
        mockMvc(participantId -> Optional.empty(), new FakeLedgerPort(), new FakeNavCache())
            .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Participant not found"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(PARTICIPANT.toString());
  }

  @Test
  void aMalformedIdIsFourHundredWithoutEchoingIt() throws Exception {
    String body =
        mockMvc(resolving(), new FakeLedgerPort(), new FakeNavCache())
            .perform(get("/v1/participants/{id}/balance", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("not-a-uuid");
  }

  /**
   * An unreachable ledger is a 503 — never a zero balance, which would read as "my money is gone".
   *
   * <p>A price is supplied deliberately: without one the request never reaches the ledger at all,
   * because an empty price set is itself refused. This must exercise the ledger failure, not that
   * one.
   */
  @Test
  void anUnreachableLedgerIsFiveOhThree() throws Exception {
    String body =
        mockMvc(
                resolving(),
                new FakeLedgerPort().unavailable(),
                new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1))
            .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Ledger unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("investedValueCents").doesNotContain("\"0\"");
  }

  /** A stale price is served, marked stale — never withheld. */
  @Test
  void aStalePriceIsServedAndMarked() throws Exception {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeLedgerPort ledger = new FakeLedgerPort();
    String scope = ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + fund;
    ledger.withUnitAccount(
        accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
        LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
        1001L,
        1_00000000L);

    mockMvc(resolving(), ledger, new FakeNavCache().with(fund, 10_00000000L, 9, true))
        .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(true))
        .andExpect(jsonPath("$.positions[0].nav.stale").value(true))
        .andExpect(jsonPath("$.investedValueCents").value(1_000));
  }

  /** No prices is a 503, never a 200 reporting zero pesos. */
  @Test
  void anEmptyPriceSetIsFiveOhThreeRatherThanAConfidentZero() throws Exception {
    String body =
        mockMvc(resolving(), new FakeLedgerPort(), new FakeNavCache())
            .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Prices unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("investedValueCents");
  }

  /**
   * A PostgreSQL outage on a participant this instance has never resolved is a 503, not the 500 it
   * fell through to before an adversarial review caught the gap — the service is not broken, a
   * dependency is down, and the retry is safe.
   */
  @Test
  void anUnreadableParticipantDirectoryIsFiveOhThree() throws Exception {
    ParticipantDirectory down =
        participantId -> {
          throw new org.springframework.dao.DataAccessResourceFailureException("postgres is down");
        };

    mockMvc(down, new FakeLedgerPort(), new FakeNavCache())
        .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Participant directory unavailable"));
  }

  /**
   * A blank {@code participant_internal_ref} is a fault in our own data, not the caller's request.
   * It must not be reported as "participantId is not a valid UUID", which would be closed as a bad
   * request and page nobody.
   */
  @Test
  void aDataIntegrityFaultIsNotReportedAsAMalformedId() throws Exception {
    ParticipantDirectory blankRef =
        participantId -> Optional.of(new ParticipantScope(ORGANIZATION, "   "));

    mockMvc(blankRef, new FakeLedgerPort(), new FakeNavCache())
        .perform(get("/v1/participants/{id}/balance", PARTICIPANT))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("Internal error"));
  }

  // -------------------------------------------------------------------------------------------

  private ParticipantDirectory resolving() {
    return participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF));
  }

  private MockMvc mockMvc(
      ParticipantDirectory directory, FakeLedgerPort ledger, FakeNavCache prices) {
    BalanceService service = new BalanceService(directory, ledger, prices, accountIds, clock);
    return MockMvcBuilders.standaloneSetup(new BalanceController(service))
        .setControllerAdvice(new BalanceExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
        .build();
  }

  private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
    return com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  /**
   * No JSON number anywhere in the body carries a decimal point or an exponent. Cash, units and NAV
   * are integers in storage, transport and computation (AGENTS.md §4); a decimal in transport is
   * the leak that would let one back in.
   */
  private static void assertNoDecimalNumbers(String body) {
    assertThat(Pattern.compile(":\\s*-?\\d+\\.\\d").matcher(body).find())
        .as("a JSON number with a decimal point in: %s", body)
        .isFalse();
    assertThat(Pattern.compile(":\\s*-?\\d+[eE][-+]?\\d").matcher(body).find())
        .as("a JSON number in exponent form in: %s", body)
        .isFalse();
  }
}
