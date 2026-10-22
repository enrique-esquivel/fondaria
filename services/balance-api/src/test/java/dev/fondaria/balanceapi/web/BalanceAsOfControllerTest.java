package dev.fondaria.balanceapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.fondaria.balanceapi.asof.AsOfBalanceService;
import dev.fondaria.balanceapi.asof.HistoricHolding;
import dev.fondaria.balanceapi.asof.HistoricUnitsRepository;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP surface of {@code GET /v1/participants/{participantId}/balance-as-of/{date}}: status
 * codes, problem details, and the shape of the JSON.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest} — no database, no context. The service
 * is the real one, wired to lambda fakes, exactly as {@code MovementControllerTest} wires the
 * movement service: the point is which failure becomes which status, and a real service is what
 * proves the exception actually reaches the advice.
 *
 * <p>What the service <i>decides</i> — which holding applies on which date, when a 404 is owed — is
 * covered by {@code AsOfBalanceServiceTest}.
 */
class BalanceAsOfControllerTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());
  private static final FundId FUND = FundId.of(UUID.randomUUID());
  private static final LocalDate DATE = LocalDate.of(2026, 3, 10);

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);

  @Test
  @DisplayName("a balance renders units, value and the NAV version behind it, all as integers")
  void aBalanceRendersIntegers() throws Exception {
    String body =
        mockMvc(resolving(), holding(1_000_00000000L), nav(12_50000000L, 77L, null))
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.participantId").value(PARTICIPANT.toString()))
            .andExpect(jsonPath("$.date").value("2026-03-10"))
            .andExpect(jsonPath("$.valueCents").value(1_250_000))
            .andExpect(jsonPath("$.positions[0].fundId").value(FUND.toString()))
            .andExpect(jsonPath("$.positions[0].unitsScaled").value(1_000_00000000L))
            .andExpect(jsonPath("$.positions[0].valueCents").value(1_250_000))
            .andExpect(jsonPath("$.positions[0].nav.navScaled").value(12_50000000L))
            .andExpect(jsonPath("$.positions[0].nav.navVersionId").value(77))
            .andExpect(jsonPath("$.positions[0].nav.valuationDate").value("2026-03-10"))
            .andExpect(jsonPath("$.positions[0].nav.stale").value(false))
            .andExpect(jsonPath("$.positions[0].nav.corrected").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoDecimalNumbers(body);
  }

  @Test
  @DisplayName("a corrected NAV is flagged on the position that used it")
  void aCorrectedNavIsFlagged() throws Exception {
    mockMvc(resolving(), holding(1_000_00000000L), nav(9_00000000L, 41L, 40L))
        .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[0].nav.corrected").value(true))
        .andExpect(jsonPath("$.positions[0].nav.navVersionId").value(41));
  }

  @Test
  @DisplayName("a holding redeemed to nothing is 200 with an empty position list and zero")
  void anEmptyHoldingIsTwoHundred() throws Exception {
    mockMvc(resolving(), holding(0L), noNav())
        .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valueCents").value(0))
        .andExpect(jsonPath("$.positions").isEmpty());
  }

  @Test
  @DisplayName("a date before the first contribution is 404, with its own problem type")
  void aDateBeforeTheFirstContributionIsFourOhFour() throws Exception {
    String body =
        mockMvc(resolving(), noHolding(), noNav())
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("No holding on record"))
            .andExpect(
                jsonPath("$.type").value("https://fondaria.example/problems/no-holding-on-record"))
            .andExpect(jsonPath("$.instance").value("/v1/participants/-/balance-as-of/-"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .as("a 404 must not be mistakable for a zero balance, and must not echo the id")
        .doesNotContain("valueCents")
        .doesNotContain(PARTICIPANT.toString());
  }

  @Test
  @DisplayName("an unknown participant is a different 404 from a date out of range")
  void anUnknownParticipantIsADistinctFourOhFour() throws Exception {
    mockMvc(participantId -> Optional.empty(), noHolding(), noNav())
        .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Participant not found"))
        .andExpect(
            jsonPath("$.type").value("https://fondaria.example/problems/participant-not-found"));
  }

  @Test
  @DisplayName("a held fund with no price for the date is 404, and the fund id is not in the body")
  void aMissingPriceIsFourOhFourWithoutNamingTheFund() throws Exception {
    String body =
        mockMvc(resolving(), holding(1_000_00000000L), noNav())
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Price not on record"))
            .andExpect(
                jsonPath("$.type").value("https://fondaria.example/problems/price-not-on-record"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .as("which funds a participant holds is not a caller's business")
        .doesNotContain(FUND.toString())
        .doesNotContain("valueCents");
  }

  @Test
  @DisplayName("a malformed participant id is 400 and never echoed")
  void aMalformedParticipantIdIsBadRequest() throws Exception {
    String body =
        mockMvc(resolving(), holding(1L), nav(1L, 1L, null))
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", "not-a-uuid", DATE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("not-a-uuid");
  }

  @Test
  @DisplayName("a malformed date is 400 and never echoed")
  void aMalformedDateIsBadRequest() throws Exception {
    String body =
        mockMvc(resolving(), holding(1L), nav(1L, 1L, null))
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, "2026-13-99"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(
                jsonPath("$.detail").value("date is not a valid ISO-8601 date (YYYY-MM-DD)."))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("2026-13-99");
  }

  @Test
  @DisplayName("an empty fund registry is 503, not a 404 that says the records do not go back")
  void anEmptyFundRegistryIsServiceUnavailable() throws Exception {
    mockMvc(resolving(), noHolding(), noNav(), List.of())
        .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Fund registry unavailable"))
        .andExpect(
            jsonPath("$.type")
                .value("https://fondaria.example/problems/fund-registry-unavailable"));
  }

  @Test
  @DisplayName("an unreadable projection is 503, never an empty or zero answer")
  void anUnreadableProjectionIsServiceUnavailable() throws Exception {
    HistoricUnitsRepository failing =
        (tenantId, accounts, asOfDate) -> {
          throw new DataAccessResourceFailureException("connection refused to db-host:5432");
        };

    String body =
        mockMvc(resolving(), failing, noNav())
            .perform(get("/v1/participants/{id}/balance-as-of/{date}", PARTICIPANT, DATE))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Historical balance unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .as("a hostname from an exception message must never reach a problem detail")
        .doesNotContain("db-host")
        .doesNotContain("valueCents");
  }

  // -------------------------------------------------------------------------------------------

  private MockMvc mockMvc(
      ParticipantDirectory directory, HistoricUnitsRepository holdings, FundNavRepository navs) {
    return mockMvc(directory, holdings, navs, List.of(new RegisteredFund(FUND, 1001L)));
  }

  private MockMvc mockMvc(
      ParticipantDirectory directory,
      HistoricUnitsRepository holdings,
      FundNavRepository navs,
      List<RegisteredFund> registry) {
    FundDirectory funds = () -> registry;
    AsOfBalanceService service =
        new AsOfBalanceService(directory, funds, holdings, navs, accountIds, tenantRefs);
    return MockMvcBuilders.standaloneSetup(new BalanceAsOfController(service))
        .setControllerAdvice(new BalanceAsOfExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
        .build();
  }

  private static ParticipantDirectory resolving() {
    return participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF));
  }

  /**
   * The vested lot holding {@code unitsScaled} on the requested date, echoing back the first
   * account id the service derived — so the fake never has to reproduce the derivation.
   */
  private static HistoricUnitsRepository holding(long unitsScaled) {
    return (tenantId, accounts, asOfDate) ->
        List.of(new HistoricHolding(accounts.get(0), asOfDate, Units.of(unitsScaled)));
  }

  private static HistoricUnitsRepository noHolding() {
    return (tenantId, accounts, asOfDate) -> List.of();
  }

  private static FundNavRepository nav(long navScaled, long navVersionId, Long supersedesId) {
    return new StubNavRepository(
        Optional.of(
            new FundNav(
                navVersionId,
                FUND,
                DATE,
                Nav.of(navScaled, CurrencyCode.MXN),
                Units.of(1_000_00000000L),
                "TEST",
                supersedesId,
                supersedesId == null ? null : "restated by the administrator",
                Instant.parse("2026-03-11T06:00:00Z"))));
  }

  private static FundNavRepository noNav() {
    return new StubNavRepository(Optional.empty());
  }

  /** One canned live price, and every other operation refused. */
  private record StubNavRepository(Optional<FundNav> live) implements FundNavRepository {

    @Override
    public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
      return live;
    }

    @Override
    public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
      throw new UnsupportedOperationException("a historical balance resolves the live price");
    }

    @Override
    public Optional<FundNav> findLatestLive(FundId fundId) {
      throw new UnsupportedOperationException("a historical balance prices its own date");
    }

    @Override
    public List<FundNav> findLatestLivePerFund() {
      throw new UnsupportedOperationException("a historical balance prices its own date");
    }

    @Override
    public FundNav strike(FundNav candidate) {
      throw new UnsupportedOperationException("balance-api never strikes a NAV");
    }

    @Override
    public FundNav correct(FundNav candidate) {
      throw new UnsupportedOperationException("balance-api never corrects a NAV");
    }
  }

  private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
    return com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  /**
   * Units, cash and NAV are integers in storage, transport and computation
   * (docs/ENGINEERING-RULES.md §4).
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
