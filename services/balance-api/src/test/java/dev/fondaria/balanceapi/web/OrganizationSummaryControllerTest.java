package dev.fondaria.balanceapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.balanceapi.summary.ContributionTotalRepository;
import dev.fondaria.balanceapi.summary.FakeContributionTotalRepository;
import dev.fondaria.balanceapi.summary.FakeOrganizationRoster;
import dev.fondaria.balanceapi.summary.OrganizationRoster;
import dev.fondaria.balanceapi.summary.OrganizationSummaryService;
import dev.fondaria.balanceapi.support.FakeLedgerPort;
import dev.fondaria.balanceapi.support.FakeNavCache;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP surface of {@code GET /v1/organizations/{organizationId}/summary}: status codes, problem
 * details, and the shape of the JSON.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest} — no database, no ledger, no context.
 * What is under test is the controller and its {@link OrganizationSummaryExceptionHandler}.
 */
class OrganizationSummaryControllerTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ACME = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void servesTheSummaryWithEveryFigureAsAnIntegerAndThePeriodItCovers() throws Exception {
    FundId fund = FundId.of(UUID.randomUUID());
    FakeLedgerPort ledger = holding(fund, 100_00000000L, 0L);

    String body =
        mockMvc(
                new FakeOrganizationRoster().with(ACME, INTERNAL_REF),
                ledger,
                new FakeNavCache().with(fund, 12_50000000L, 4242),
                new FakeContributionTotalRepository().returning(16_200_00L))
            .perform(get("/v1/organizations/{id}/summary", ACME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalParticipants").value(1))
            // 100 units at 12.50 => 1,250.00 pesos => 125,000 cents.
            .andExpect(jsonPath("$.totalValueCents").value(125_000))
            .andExpect(jsonPath("$.contributionsThisPeriodCents").value(1_620_000))
            .andExpect(jsonPath("$.periodStart").value("2026-09-01"))
            .andExpect(jsonPath("$.periodEnd").value("2026-09-30"))
            .andExpect(jsonPath("$.stale").value(false))
            .andExpect(jsonPath("$.funds[0].totalUnitsScaled").value(10_000_000_000L))
            .andExpect(jsonPath("$.funds[0].totalValueCents").value(125_000))
            .andExpect(jsonPath("$.funds[0].nav.navVersionId").value(4242))
            .andExpect(jsonPath("$.funds[0].nav.navScaled").value(1_250_000_000L))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoDecimalNumbers(body);
    assertThat(body).doesNotContain("\"totalValueCents\":\"");
  }

  @Test
  void anUnknownOrganizationIsFourOhFourWithoutEchoingTheId() throws Exception {
    String body =
        mockMvc(
                new FakeOrganizationRoster(),
                new FakeLedgerPort(),
                new FakeNavCache(),
                new FakeContributionTotalRepository())
            .perform(get("/v1/organizations/{id}/summary", ACME))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Organization not found"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(ACME.toString());
  }

  @Test
  void aMalformedIdIsFourHundredWithoutEchoingIt() throws Exception {
    String body =
        mockMvc(
                new FakeOrganizationRoster(),
                new FakeLedgerPort(),
                new FakeNavCache(),
                new FakeContributionTotalRepository())
            .perform(get("/v1/organizations/{id}/summary", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("not-a-uuid");
  }

  /** An unreachable ledger is a 503 — never a total missing an unknown number of people. */
  @Test
  void anUnreachableLedgerIsFiveOhThree() throws Exception {
    String body =
        mockMvc(
                new FakeOrganizationRoster().with(ACME, INTERNAL_REF),
                new FakeLedgerPort().unavailable(),
                new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1),
                new FakeContributionTotalRepository())
            .perform(get("/v1/organizations/{id}/summary", ACME))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Ledger unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("totalValueCents").doesNotContain("\"0\"");
  }

  @Test
  void anEmptyPriceSetIsFiveOhThreeRatherThanAConfidentZero() throws Exception {
    String body =
        mockMvc(
                new FakeOrganizationRoster().with(ACME, INTERNAL_REF),
                new FakeLedgerPort(),
                new FakeNavCache(),
                new FakeContributionTotalRepository())
            .perform(get("/v1/organizations/{id}/summary", ACME))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Prices unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("totalValueCents");
  }

  /**
   * Unlike the balance read, this endpoint needs PostgreSQL on every request — the roster and the
   * contributions figure both live there. An outage is a 503, never an empty organization.
   */
  @Test
  void anUnreadableDatabaseIsFiveOhThree() throws Exception {
    OrganizationRoster down =
        new OrganizationRoster() {
          @Override
          public boolean isRegistered(OrganizationId organizationId) {
            throw new DataAccessResourceFailureException("postgres is down");
          }

          @Override
          public List<String> participantsOf(OrganizationId organizationId) {
            throw new DataAccessResourceFailureException("postgres is down");
          }
        };

    mockMvc(
            down,
            new FakeLedgerPort(),
            new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1),
            new FakeContributionTotalRepository())
        .perform(get("/v1/organizations/{id}/summary", ACME))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Summary data unavailable"));
  }

  /** A stale price is served, marked stale — never withheld. */
  @Test
  void aStalePriceIsServedAndMarked() throws Exception {
    FundId fund = FundId.of(UUID.randomUUID());

    mockMvc(
            new FakeOrganizationRoster().with(ACME, INTERNAL_REF),
            holding(fund, 1_00000000L, 0L),
            new FakeNavCache().with(fund, 10_00000000L, 9, true),
            new FakeContributionTotalRepository())
        .perform(get("/v1/organizations/{id}/summary", ACME))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(true))
        .andExpect(jsonPath("$.funds[0].nav.stale").value(true))
        .andExpect(jsonPath("$.totalValueCents").value(1_000));
  }

  /**
   * A blank {@code participant_internal_ref} is a fault in our own data, not the caller's request —
   * the same gap {@code BalanceControllerTest} pins on the participant route. It must not be
   * reported as a malformed organization id, which would be closed as a bad request and page
   * nobody.
   */
  @Test
  void aDataIntegrityFaultIsNotReportedAsAMalformedId() throws Exception {
    mockMvc(
            new FakeOrganizationRoster().with(ACME, "   "),
            new FakeLedgerPort(),
            new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1),
            new FakeContributionTotalRepository())
        .perform(get("/v1/organizations/{id}/summary", ACME))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.title").value("Internal error"));
  }

  /** A registered employer whose people hold nothing is a zero summary, never an error. */
  @Test
  void anOrganizationHoldingNothingIsAZeroSummaryNotAnError() throws Exception {
    String body =
        mockMvc(
                new FakeOrganizationRoster().withOrganization(ACME),
                new FakeLedgerPort(),
                new FakeNavCache().with(FundId.of(UUID.randomUUID()), 10_00000000L, 1),
                new FakeContributionTotalRepository())
            .perform(get("/v1/organizations/{id}/summary", ACME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.funds").isEmpty())
            .andExpect(jsonPath("$.totalValueCents").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoDecimalNumbers(body);
  }

  // -------------------------------------------------------------------------------------------

  private FakeLedgerPort holding(FundId fund, long vested, long unvested) {
    FakeLedgerPort ledger = new FakeLedgerPort();
    String scope = new ParticipantScope(ACME, INTERNAL_REF).forFund(fund);
    if (vested > 0) {
      ledger.withUnitAccount(
          accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope),
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
          1001L,
          vested);
    }
    if (unvested > 0) {
      ledger.withUnitAccount(
          accountIds.derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope),
          LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED,
          1001L,
          unvested);
    }
    return ledger;
  }

  private MockMvc mockMvc(
      OrganizationRoster roster,
      FakeLedgerPort ledger,
      FakeNavCache prices,
      ContributionTotalRepository contributions) {
    OrganizationSummaryService service =
        new OrganizationSummaryService(
            roster,
            ledger,
            prices,
            contributions,
            accountIds,
            tenantRefs,
            clock,
            ZoneId.of("America/Mexico_City"));
    return MockMvcBuilders.standaloneSetup(new OrganizationSummaryController(service))
        .setControllerAdvice(new OrganizationSummaryExceptionHandler())
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
   * are integers in storage, transport and computation (docs/ENGINEERING-RULES.md §4).
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
