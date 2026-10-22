package dev.fondaria.contribution.organization;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Task 2b.1 end to end: HTTP in, PostgreSQL and TigerBeetle out.
 *
 * <p>Everything here registers an employer whose {@code RFC} has never existed and asserts only
 * over what it created itself. The fixtures are reused between runs and TigerBeetle cannot be reset
 * (docs/ENGINEERING-RULES.md §4), so a count over a whole table or ledger would pass or fail on
 * what a previous run left behind.
 *
 * <p><b>What the log assertion covers, and what it deliberately does not.</b> The appender sits on
 * the root logger, so it captures whatever the service actually emits at its configured levels;
 * {@code dev.fondaria} is turned up to {@code TRACE} so that this codebase's own logging is held to
 * the strictest standard. Spring's own request-body tracing is left at its normal level on purpose:
 * at {@code TRACE} the framework logs raw request bodies, so a registration body would be captured
 * — which is a real reason never to run {@code TRACE} in production, not a defect in this code, and
 * asserting against it here would only prove that the framework was turned down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationRegistrationIntegrationTest extends ServiceIntegrationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private OrganizationRepository repository;

  private ListAppender<ILoggingEvent> logs;
  private Level applicationLevelBefore;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    logs.start();
    rootLogger().addAppender(logs);
    applicationLevelBefore = applicationLogger().getLevel();
    applicationLogger().setLevel(Level.TRACE);
  }

  @AfterEach
  void releaseLogs() {
    rootLogger().detachAppender(logs);
    // Logger levels are JVM-wide; a later class that assumes the configured level must get it.
    applicationLogger().setLevel(applicationLevelBefore);
  }

  @Test
  @DisplayName("registering the same tax identifier twice yields one organization")
  void registeringTwiceYieldsOneOrganization() {
    String rfc = FreshTaxIdentifiers.freshRfc();

    ResponseEntity<Map<String, Object>> first = register(rfc, "Acme S.A. de C.V.");
    ResponseEntity<Map<String, Object>> second = register(rfc, "Acme S.A. de C.V.");

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    String organizationId = idOf(first);
    assertThat(idOf(second)).isEqualTo(organizationId);
    assertThat(repository.findById(OrganizationId.parse(organizationId))).isPresent();
  }

  @Test
  @DisplayName("registering the same tax identifier twice yields one ledger account")
  void registeringTwiceYieldsOneLedgerAccount() {
    String rfc = FreshTaxIdentifiers.freshRfc();

    OrganizationId organizationId = OrganizationId.parse(idOf(register(rfc, "Acme")));
    register(rfc, "Acme");

    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    Optional<LedgerAccount> account = ledger.lookupAccount(collections);
    assertThat(account).isPresent();
    assertThat(account.get())
        .isInstanceOfSatisfying(
            LedgerAccount.Cash.class,
            cash -> {
              assertThat(cash.accountCode())
                  .isEqualTo(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS);
              // A second registration must not have posted anything, only re-opened.
              assertThat(cash.balance()).isEqualTo(Money.of(0L, CurrencyCode.MXN));
            });
    assertThat(ledger.lookupAccount(accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING)))
        .as("BANK_CLEARING is opened once, platform-wide")
        .isPresent();
  }

  @Test
  @DisplayName("an organization can be listed and looked up by id")
  void canBeListedAndLookedUp() {
    String rfc = FreshTaxIdentifiers.freshRfc();
    String organizationId = idOf(register(rfc, "Acme Nómina"));

    ResponseEntity<Map<String, Object>> byId =
        http.exchange(
            "/v1/organizations/" + organizationId,
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});
    ResponseEntity<List<Map<String, Object>>> listed =
        http.exchange(
            "/v1/organizations?limit=200",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});

    assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(byId.getBody()).containsEntry("legalName", "Acme Nómina");
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(listed.getBody())
        .as("only the organization this test created is asserted over")
        .anySatisfy(row -> assertThat(row).containsEntry("organizationId", organizationId));
  }

  @Test
  @DisplayName("an unknown organization id is a 404 problem detail, not an empty 200")
  void unknownIdIsNotFound() {
    ResponseEntity<Map<String, Object>> response =
        http.exchange(
            "/v1/organizations/" + java.util.UUID.randomUUID(),
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("title", "Organization not found");
  }

  @Test
  @DisplayName("updating the legal name leaves the id and the ledger account unchanged")
  void updateLeavesIdAndLedgerAccountUnchanged() {
    String rfc = FreshTaxIdentifiers.freshRfc();
    String organizationId = idOf(register(rfc, "Acme S.A. de C.V."));
    AccountId collections =
        accountIds.derive(
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS,
            OrganizationId.parse(organizationId).scope());
    LedgerAccount before = ledger.lookupAccount(collections).orElseThrow();

    ResponseEntity<Map<String, Object>> updated =
        http.exchange(
            "/v1/organizations/" + organizationId,
            HttpMethod.PATCH,
            json("{\"legalName\":\"Acme Servicios S.A. de C.V.\"}"),
            new ParameterizedTypeReference<>() {});

    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody())
        .containsEntry("organizationId", organizationId)
        .containsEntry("legalName", "Acme Servicios S.A. de C.V.")
        .containsEntry("revision", 2);
    assertThat(ledger.lookupAccount(collections)).contains(before);
  }

  @Test
  @DisplayName("an update appends a revision; the superseded one stays readable")
  void updateAppendsRevision() {
    String organizationId = idOf(register(FreshTaxIdentifiers.freshRfc(), "Acme"));

    http.exchange(
        "/v1/organizations/" + organizationId,
        HttpMethod.PATCH,
        json("{\"legalName\":\"Acme Servicios\",\"status\":\"SUSPENDED\"}"),
        new ParameterizedTypeReference<Map<String, Object>>() {});

    ResponseEntity<List<Map<String, Object>>> revisions =
        http.exchange(
            "/v1/organizations/" + organizationId + "/revisions",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {});
    assertThat(revisions.getBody()).hasSize(2);
    assertThat(revisions.getBody().get(0))
        .containsEntry("legalName", "Acme")
        .containsEntry("status", "ACTIVE");
    assertThat(revisions.getBody().get(1))
        .containsEntry("legalName", "Acme Servicios")
        .containsEntry("status", "SUSPENDED");
  }

  @Test
  @DisplayName("an ill-formed RFC is a 400 problem detail that does not echo the value")
  void illFormedTaxIdentifierIsRejected() {
    ResponseEntity<Map<String, Object>> response =
        http.exchange(
            "/v1/organizations",
            HttpMethod.POST,
            json("{\"taxIdScheme\":\"MX_RFC\",\"taxId\":\"TOO-SHORT\",\"legalName\":\"Acme\"}"),
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(String.valueOf(response.getBody())).doesNotContain("TOOSHORT");
  }

  @Test
  @DisplayName("the tax identifier appears in no log produced by a full registration")
  void fullRegistrationLogsNoTaxIdentifier() {
    String rfc = FreshTaxIdentifiers.freshRfc();

    String organizationId = idOf(register(rfc, "Acme S.A. de C.V."));
    http.exchange(
        "/v1/organizations/" + organizationId,
        HttpMethod.PATCH,
        json("{\"legalName\":\"Acme Servicios\"}"),
        new ParameterizedTypeReference<Map<String, Object>>() {});

    assertThat(logs.list).isNotEmpty();
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage())
          .as("log line from a full registration must not carry the RFC")
          .doesNotContain(rfc)
          .doesNotContain(rfc.toLowerCase(java.util.Locale.ROOT));
      if (event.getArgumentArray() != null) {
        for (Object argument : event.getArgumentArray()) {
          assertThat(String.valueOf(argument)).doesNotContain(rfc);
        }
      }
    }
  }

  private ResponseEntity<Map<String, Object>> register(String rfc, String legalName) {
    return http.exchange(
        "/v1/organizations",
        HttpMethod.POST,
        json(
            "{\"taxIdScheme\":\"MX_RFC\",\"taxId\":\""
                + rfc
                + "\",\"legalName\":\""
                + legalName
                + "\",\"contractReference\":\"CT-2026-0001\",\"feeBracket\":\"BRACKET_A\"}"),
        new ParameterizedTypeReference<>() {});
  }

  private static HttpEntity<String> json(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  private static String idOf(ResponseEntity<Map<String, Object>> response) {
    return String.valueOf(response.getBody().get("organizationId"));
  }

  private static ch.qos.logback.classic.Logger rootLogger() {
    return (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  /** This codebase's own logging, held to a stricter level than the frameworks around it. */
  private static ch.qos.logback.classic.Logger applicationLogger() {
    return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dev.fondaria");
  }
}
