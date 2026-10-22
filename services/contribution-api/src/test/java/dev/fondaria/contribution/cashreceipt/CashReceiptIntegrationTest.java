package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.Organization;
import dev.fondaria.contribution.organization.OrganizationRepository;
import dev.fondaria.contribution.organization.OrganizationStatus;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Task 3.3 end to end: HTTP in, PostgreSQL and TigerBeetle out, with a live Redis.
 *
 * <p><b>Nothing here is asserted over a total.</b> Every test registers an employer whose {@code
 * RFC} has never existed, so its {@code EMPLOYER_COLLECTIONS} account starts at zero and every
 * balance assertion is over money this test moved. The fixtures are reused between runs and
 * TigerBeetle cannot be reset (AGENTS.md §4); a count over the whole cash ledger would pass or fail
 * on what a previous run left there, which is the worst failure mode available — intermittent, and
 * about money.
 *
 * <p>"Exactly one movement" is asserted two ways on purpose: the account balance moved by the
 * receipt's amount and no more, and the derived transfer id resolves to exactly one transfer of
 * that amount.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CashReceiptIntegrationTest extends ServiceIntegrationTest {

  private static final long AMOUNT = 250_000L;
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);
  private static final String BANK_REFERENCE = "SPEI-2026-08-18-0001";
  private static final String OPERATOR = "ops-marta";

  @Autowired private TestRestTemplate http;
  @Autowired private LedgerPort ledger;
  @Autowired private AccountIdDeriver accountIds;
  @Autowired private TransferIdDeriver transferIds;
  @Autowired private CashReceiptRepository receipts;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("a receipt with no matching file is accepted and rests in EMPLOYER_COLLECTIONS")
  void moneyWithoutAFileRestsInCollections() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();

    ResponseEntity<Map<String, Object>> response = post(organizationId, key, AMOUNT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody())
        .containsEntry("status", "POSTED")
        .containsEntry("amountMinorUnits", (int) AMOUNT)
        .containsEntry("currency", "MXN");
    // Read through LedgerPort.lookupAccount, never summed from PostgreSQL (ADR-0011).
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
    assertThat(receipts.find(organizationId, key))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.POSTED);
  }

  @Test
  @DisplayName("resubmitting an identical receipt returns the original response, one movement")
  void resubmittingReturnsTheOriginalAndMovesOnce() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();

    ResponseEntity<Map<String, Object>> first = post(organizationId, key, AMOUNT);
    ResponseEntity<Map<String, Object>> second = post(organizationId, key, AMOUNT);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode())
        .as("a retry is the same statement again, not a second creation")
        .isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
    assertThat(movementAmount(organizationId, key)).contains(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("the same key with a different amount is 422, and nothing further moves")
  void sameKeyDifferentAmountIsUnprocessable() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();
    post(organizationId, key, AMOUNT);

    ResponseEntity<Map<String, Object>> reused = post(organizationId, key, AMOUNT + 1);

    assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(reused.getBody()).containsEntry("title", "Idempotency key reused");
    assertThat(collectionsBalance(organizationId))
        .as("the original amount, and only the original amount")
        .isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("two concurrent identical submissions: one accepted, one 409, one movement")
  void concurrentDuplicatesYieldOneMovement() throws Exception {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();
    CountDownLatch start = new CountDownLatch(1);

    List<HttpStatus> statuses;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Callable<HttpStatus> submit =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            return (HttpStatus) post(organizationId, key, AMOUNT).getStatusCode();
          };
      Future<HttpStatus> left = pool.submit(submit);
      Future<HttpStatus> right = pool.submit(submit);
      start.countDown();
      statuses = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
    }

    assertThat(statuses)
        .as("exactly one submission posts the movement")
        .containsOnlyOnce(HttpStatus.CREATED);
    assertThat(statuses)
        .as(
            "the other either lost the claim (409) or arrived after the winner finished and"
                + " replayed it (200) — never a second creation")
        .allMatch(
            status ->
                status == HttpStatus.CREATED
                    || status == HttpStatus.CONFLICT
                    || status == HttpStatus.OK);
    assertThat(collectionsBalance(organizationId))
        .as("one movement, whichever request won")
        .isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("a submission whose receipt is claimed elsewhere is a 409, deterministically")
  void aClaimedReceiptIsAConflict() throws SQLException {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();
    // An UNCONFIRMED receipt, which is what an in-flight request's row actually looks
    // like — unresolved, and therefore uncached, because only a resolved receipt is
    // ever cached. Using a completed receipt here would prove nothing: the cache would
    // answer 200 before PostgreSQL was consulted at all, which is correct behaviour and
    // not the behaviour under test.
    givenAnUnconfirmedReceipt(organizationId, key);

    // Holds the claim exactly as an in-flight request does, without the timing race
    // that makes the concurrent test above unable to insist on a 409.
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute(
            "SELECT 1 FROM workflow.cash_receipt WHERE organization_id = '"
                + organizationId
                + "' AND idempotency_key = '"
                + key
                + "' FOR UPDATE");
      }

      ResponseEntity<Map<String, Object>> blocked = post(organizationId, key, AMOUNT);

      assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(blocked.getBody()).containsEntry("title", "Cash receipt in flight");
      holder.rollback();
    }
    assertThat(collectionsBalance(organizationId))
        .as("a conflict moves nothing; the claim's holder decides what happens")
        .isEqualTo(Money.of(0L, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("a crash before the ledger post is repaired by a retry: one movement")
  void aCrashBeforeThePostConverges() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();
    givenAnUnconfirmedReceipt(organizationId, key);

    ResponseEntity<Map<String, Object>> retry = post(organizationId, key, AMOUNT);

    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(receipts.find(organizationId, key))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.POSTED);
    assertThat(collectionsBalance(organizationId)).isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("a crash after the ledger post is repaired by a retry: still one movement")
  void aCrashAfterThePostConverges() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();
    // The exact state a crash between the two writes leaves, reconstructed rather than
    // simulated: the row committed as UNCONFIRMED, the money already moved, the status
    // update never reached, and nothing cached — because the cache is written last.
    // ADR-0011 chose this write order so this state is reconcilable, and this is the
    // case a naive retry would double-credit.
    givenAnUnconfirmedReceipt(organizationId, key);
    givenTheMovementAlreadyPosted(organizationId, key);

    ResponseEntity<Map<String, Object>> retry = post(organizationId, key, AMOUNT);

    assertThat(retry.getStatusCode())
        .as("the money moved before this call, so this call did not create it")
        .isEqualTo(HttpStatus.OK);
    assertThat(retry.getBody()).containsEntry("status", "POSTED");
    assertThat(receipts.find(organizationId, key))
        .as("the row is repaired rather than left unreconciled forever")
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.POSTED);
    assertThat(collectionsBalance(organizationId))
        .as("the retry converged on the existing movement rather than making a second")
        .isEqualTo(Money.of(AMOUNT, CurrencyCode.MXN));
  }

  @Test
  @DisplayName("a ledger rejection leaves a REJECTED row and moves nothing")
  void aLedgerRejectionIsRecorded() {
    // An organization row with no ledger accounts — the state a crash during registration
    // leaves, and the one realistic way this endpoint meets ACCOUNT_NOT_FOUND. Written
    // through the repository rather than the service precisely because the service would
    // provision the accounts.
    OrganizationId organizationId = registerOrganizationWithoutLedgerAccounts();
    String key = freshKey();

    ResponseEntity<Map<String, Object>> response = post(organizationId, key, AMOUNT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody())
        .containsEntry("title", "Cash receipt rejected by the ledger")
        .containsEntry("rejectionReason", "ACCOUNT_NOT_FOUND");
    assertThat(receipts.find(organizationId, key))
        .as("a refused movement still leaves a row saying what was attempted and why it failed")
        .get()
        .extracting(CashReceipt::status, r -> r.rejectionReason().orElse(null))
        .containsExactly(CashReceiptStatus.REJECTED, "ACCOUNT_NOT_FOUND");
    assertThat(ledger.lookupTransfer(transferIds.derive(organizationId.toString(), key)))
        .as("nothing moved")
        .isEmpty();
  }

  @Test
  @DisplayName("a rejection is replayed from cache as the same 422, not retried against the ledger")
  void aRejectionIsReplayed() {
    OrganizationId organizationId = registerOrganizationWithoutLedgerAccounts();
    String key = freshKey();
    post(organizationId, key, AMOUNT);

    ResponseEntity<Map<String, Object>> replay = post(organizationId, key, AMOUNT);

    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(replay.getBody()).containsEntry("rejectionReason", "ACCOUNT_NOT_FOUND");
  }

  @Test
  @DisplayName("a receipt for an unregistered organization is a 404, and opens no account")
  void anUnknownOrganizationIsNotFound() {
    OrganizationId unknown = new OrganizationId(UUID.randomUUID());

    ResponseEntity<Map<String, Object>> response = post(unknown, freshKey(), AMOUNT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).containsEntry("title", "Organization not found");
    assertThat(
            ledger.lookupAccount(
                accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, unknown.scope())))
        .as("a well-formed id belonging to nobody must never open an account")
        .isEmpty();
  }

  @Test
  @DisplayName("the Idempotency-Key header is required, and its absence is explained")
  void theHeaderIsRequired() {
    OrganizationId organizationId = registerOrganization();

    ResponseEntity<Map<String, Object>> response =
        http.exchange(
            "/v1/organizations/" + organizationId + "/cash-receipts",
            HttpMethod.POST,
            json(body(AMOUNT), null),
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(String.valueOf(response.getBody())).contains("Idempotency-Key");
  }

  @Test
  @DisplayName("a non-positive amount is a 400 before anything is recorded")
  void nonPositiveAmountIsRejected() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();

    ResponseEntity<Map<String, Object>> response = post(organizationId, key, 0L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(receipts.find(organizationId, key)).isEmpty();
  }

  @Test
  @DisplayName("the response carries the derived transfer id and never the idempotency key")
  void responseCarriesTheTieOutHandleAndNotTheKey() {
    OrganizationId organizationId = registerOrganization();
    String key = freshKey();

    ResponseEntity<Map<String, Object>> response = post(organizationId, key, AMOUNT);

    TransferId expected = transferIds.derive(organizationId.toString(), key);
    assertThat(response.getBody())
        .containsEntry("transferId", TransferIdDeriver.toUuid(expected).toString());
    assertThat(String.valueOf(response.getBody()))
        .as("an idempotency key is on the never-log list, and a response body reaches logs")
        .doesNotContain(key);
  }

  // -----------------------------------------------------------------------------------------

  private ResponseEntity<Map<String, Object>> post(
      OrganizationId organizationId, String idempotencyKey, long cents) {
    return http.exchange(
        "/v1/organizations/" + organizationId + "/cash-receipts",
        HttpMethod.POST,
        json(body(cents), idempotencyKey),
        new ParameterizedTypeReference<>() {});
  }

  private static String body(long cents) {
    return "{\"amountMinorUnits\":"
        + cents
        + ",\"currency\":\"MXN\",\"valueDate\":\""
        + VALUE_DATE
        + "\",\"bankReference\":\""
        + BANK_REFERENCE
        + "\",\"operatorId\":\""
        + OPERATOR
        + "\"}";
  }

  private static HttpEntity<String> json(String body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return new HttpEntity<>(body, headers);
  }

  private static String freshKey() {
    return "key-" + UUID.randomUUID();
  }

  private Money collectionsBalance(OrganizationId organizationId) {
    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    LedgerAccount account = ledger.lookupAccount(collections).orElseThrow();
    return ((LedgerAccount.Cash) account).balance();
  }

  private Optional<Money> movementAmount(OrganizationId organizationId, String key) {
    return ledger
        .lookupTransfer(transferIds.derive(organizationId.toString(), key))
        .map(transfer -> ((LedgerTransfer.Cash) transfer).amount());
  }

  private OrganizationId registerOrganization() {
    ResponseEntity<Map<String, Object>> response =
        http.exchange(
            "/v1/organizations",
            HttpMethod.POST,
            json(
                "{\"taxIdScheme\":\"MX_RFC\",\"taxId\":\""
                    + FreshTaxIdentifiers.freshRfc()
                    + "\",\"legalName\":\"Acme S.A. de C.V.\"}",
                null),
            new ParameterizedTypeReference<>() {});
    return OrganizationId.parse(String.valueOf(response.getBody().get("organizationId")));
  }

  private OrganizationId registerOrganizationWithoutLedgerAccounts() {
    TaxIdentifier taxIdentifier = FreshTaxIdentifiers.fresh();
    OrganizationId organizationId = OrganizationId.derive(ORGANIZATION_NAMESPACE, taxIdentifier);
    Instant now = Instant.now();
    organizationRepository.insertIfAbsent(
        new Organization(
            organizationId,
            taxIdentifier,
            "Acme Sin Cuentas S.A. de C.V.",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            1,
            now,
            now));
    return organizationId;
  }

  /** The committed {@code UNCONFIRMED} row, written as the service writes it and then abandoned. */
  private void givenAnUnconfirmedReceipt(OrganizationId organizationId, String key) {
    Instant now = Instant.now();
    receipts.recordUnconfirmed(
        new CashReceipt(
            organizationId,
            key,
            transferIds.derive(organizationId.toString(), key),
            Money.of(AMOUNT, CurrencyCode.MXN),
            VALUE_DATE,
            BANK_REFERENCE,
            OPERATOR,
            CashReceiptFingerprint.of(
                new RecordCashReceiptCommand(
                    organizationId,
                    key,
                    Money.of(AMOUNT, CurrencyCode.MXN),
                    VALUE_DATE,
                    BANK_REFERENCE,
                    OPERATOR)),
            CashReceiptStatus.UNCONFIRMED,
            Optional.empty(),
            now,
            now));
  }

  /** The movement the crashed attempt did get as far as making. */
  private void givenTheMovementAlreadyPosted(OrganizationId organizationId, String key) {
    ledger.createTransfer(
        CreateTransferCommand.Cash.single(
            transferIds.derive(organizationId.toString(), key),
            accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING),
            accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()),
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(AMOUNT, CurrencyCode.MXN),
            ExternalReference.derive(LEDGER_NAMESPACE, BANK_REFERENCE)));
  }
}
