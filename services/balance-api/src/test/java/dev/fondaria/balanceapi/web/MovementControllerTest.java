package dev.fondaria.balanceapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.fund.RegisteredFund;
import dev.fondaria.balanceapi.movement.CursorCodec;
import dev.fondaria.balanceapi.movement.MovementCursor;
import dev.fondaria.balanceapi.movement.MovementDirection;
import dev.fondaria.balanceapi.movement.MovementLeg;
import dev.fondaria.balanceapi.movement.MovementQuery;
import dev.fondaria.balanceapi.movement.MovementRepository;
import dev.fondaria.balanceapi.movement.MovementService;
import dev.fondaria.balanceapi.participant.ParticipantScope;
import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP surface of {@code GET /v1/participants/{participantId}/movements}: status codes, problem
 * details, and the shape of the JSON.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest} — no database, no context. What is
 * under test is the controller and its {@link MovementExceptionHandler}.
 */
class MovementControllerTest {

  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());
  private static final String INTERNAL_REF = "EMP-000123";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());
  private static final FundId FUND = FundId.of(UUID.randomUUID());

  /** Any unit ledger id; this endpoint never uses it — the projection's fund key does. */
  private static final long UNIT_LEDGER_ID = 1001L;

  private final AccountIdDeriver accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
  private final TenantRefDeriver tenantRefs = new TenantRefDeriver(LEDGER_NAMESPACE);
  private final CursorCodec cursors = new CursorCodec("a-test-signing-key-of-adequate-length");

  private final String vested =
      accountIds
          .derive(
              LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED,
              ORGANIZATION.scope() + ":" + INTERNAL_REF + ":" + FUND)
          .asUnsignedDecimal();

  @Test
  @DisplayName("a page renders every quantity as an integer and every ledger id as a string")
  void aPageRendersIntegersAndIds() throws Exception {
    String body =
        mockMvc(repositoryWith(allotment("1735689600123456789", "1934287198347198347198")))
            .perform(get("/v1/participants/{id}/movements", PARTICIPANT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.items[0].unitsScaled").value(100_000_000_000L))
            .andExpect(jsonPath("$.items[0].holdingAccount").value("VESTED"))
            .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
            .andExpect(jsonPath("$.items[0].transferCode").value("CONTRIBUTION_ALLOTMENT"))
            .andExpect(jsonPath("$.items[0].navVersionId").value(4242))
            .andExpect(jsonPath("$.items[0].fundId").value(FUND.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // A u128 and a u64 that would lose their low digits if a client parsed them as JSON numbers.
    assertThat(body).contains("\"transferId\":\"1934287198347198347198\"");
    assertThat(body).contains("\"ledgerTimestamp\":\"1735689600123456789\"");
    assertNoDecimalNumbers(body);
  }

  @Test
  @DisplayName("a tampered cursor is 400, and says nothing about how it failed")
  void aTamperedCursorIsFourHundred() throws Exception {
    String body =
        mockMvc(repositoryWith(allotment("100", "1")))
            .perform(
                get("/v1/participants/{id}/movements", PARTICIPANT).param("cursor", "AAAA.BBBB"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid cursor"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("AAAA");
  }

  /**
   * A cursor this service issued for this query is accepted, so the 400 above is about tampering.
   */
  @Test
  @DisplayName("a cursor this service issued is accepted")
  void anIssuedCursorIsAccepted() throws Exception {
    MovementQuery query =
        new MovementQuery(
            PARTICIPANT,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            MovementQuery.DEFAULT_LIMIT,
            Optional.empty());
    String cursor =
        cursors.encode(
            query,
            new MovementCursor(
                new java.math.BigInteger("200"),
                new java.math.BigInteger("2"),
                MovementDirection.CREDIT.leg()));

    mockMvc(repositoryWith(allotment("100", "1")))
        .perform(get("/v1/participants/{id}/movements", PARTICIPANT).param("cursor", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].ledgerTimestamp").value("100"));
  }

  @Test
  @DisplayName("a page size outside 1..200 is 400 without echoing what was sent")
  void anOutOfRangePageSizeIsFourHundred() throws Exception {
    String body =
        mockMvc(repositoryWith(allotment("100", "1")))
            .perform(get("/v1/participants/{id}/movements", PARTICIPANT).param("limit", "9999"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("9999");
  }

  @Test
  @DisplayName("a malformed filter is 400, and the offending text is not reflected back")
  void aMalformedFilterIsFourHundred() throws Exception {
    MockMvc mockMvc = mockMvc(repositoryWith(allotment("100", "1")));

    assertThat(
            mockMvc
                .perform(
                    get("/v1/participants/{id}/movements", PARTICIPANT)
                        .param("from", "the-third-of-august"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .doesNotContain("the-third-of-august");

    assertThat(
            mockMvc
                .perform(
                    get("/v1/participants/{id}/movements", PARTICIPANT)
                        .param("transferCode", "CASH_RECEIPT"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .as("a cash code cannot post to a holding account, so it is not a legal filter")
        .contains("CONTRIBUTION_ALLOTMENT");

    mockMvc
        .perform(get("/v1/participants/{id}/movements", PARTICIPANT).param("fundId", "not-a-uuid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an unknown participant is 404 without echoing the id")
  void anUnknownParticipantIsFourOhFour() throws Exception {
    MovementService service =
        new MovementService(
            participantId -> Optional.empty(),
            (FundDirectory) () -> List.of(new RegisteredFund(FUND, UNIT_LEDGER_ID)),
            repositoryWith(allotment("100", "1")),
            accountIds,
            tenantRefs,
            cursors);

    String body =
        mockMvc(service)
            .perform(get("/v1/participants/{id}/movements", PARTICIPANT))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Participant not found"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(PARTICIPANT.toString());
  }

  @Test
  @DisplayName("a malformed participant id is 400 without echoing it")
  void aMalformedParticipantIdIsFourHundred() throws Exception {
    String body =
        mockMvc(repositoryWith(allotment("100", "1")))
            .perform(get("/v1/participants/{id}/movements", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("not-a-uuid");
  }

  /**
   * A history is a PostgreSQL read by its nature, so an outage is visible — as a 503, never as an
   * empty page, which would read as "nothing ever happened to my money".
   */
  @Test
  @DisplayName("an unreadable projection is 503, never an empty page")
  void anUnreadableProjectionIsFiveOhThree() throws Exception {
    MovementRepository down =
        (tenantId, accountIds, query, cursor, rowLimit) -> {
          throw new org.springframework.dao.DataAccessResourceFailureException("postgres is down");
        };

    String body =
        mockMvc(down)
            .perform(get("/v1/participants/{id}/movements", PARTICIPANT))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("Movement history unavailable"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("items");
  }

  // -------------------------------------------------------------------------------------------

  private MovementRepository repositoryWith(MovementLeg leg) {
    return (tenantId, accountIds, query, cursor, rowLimit) ->
        accountIds.contains(leg.accountId()) ? List.of(leg) : List.of();
  }

  private MovementLeg allotment(String ledgerTimestamp, String transferId) {
    return new MovementLeg(
        transferId,
        ledgerTimestamp,
        vested,
        MovementDirection.CREDIT,
        1_000_00000000L,
        LedgerCodes.TransferCode.CONTRIBUTION_ALLOTMENT.code(),
        LocalDate.of(2026, 8, 3),
        LocalDate.of(2026, 8, 3),
        Optional.of(4242L));
  }

  private MockMvc mockMvc(MovementRepository repository) {
    return mockMvc(
        new MovementService(
            participantId -> Optional.of(new ParticipantScope(ORGANIZATION, INTERNAL_REF)),
            (FundDirectory) () -> List.of(new RegisteredFund(FUND, UNIT_LEDGER_ID)),
            repository,
            accountIds,
            tenantRefs,
            cursors));
  }

  private MockMvc mockMvc(MovementService service) {
    return MockMvcBuilders.standaloneSetup(new MovementController(service))
        .setControllerAdvice(new MovementExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
        .build();
  }

  private static com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
    return com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  /** Units are integers in storage, transport and computation (AGENTS.md §4). */
  private static void assertNoDecimalNumbers(String body) {
    assertThat(Pattern.compile(":\\s*-?\\d+\\.\\d").matcher(body).find())
        .as("a JSON number with a decimal point in: %s", body)
        .isFalse();
    assertThat(Pattern.compile(":\\s*-?\\d+[eE][-+]?\\d").matcher(body).find())
        .as("a JSON number in exponent form in: %s", body)
        .isFalse();
  }
}
