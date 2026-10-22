package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL behind {@link CashReceiptRepository}, and what the database itself enforces, against a
 * real PostgreSQL. What the service <i>decides</i> is asserted without a database in {@link
 * CashReceiptServiceTest}; the constraints and the {@code NOWAIT} claim can only be asserted here.
 *
 * <p>Every test uses an organization and an idempotency key it created itself. Fixtures are reused
 * across runs (docs/ENGINEERING-RULES.md §4), so anything asserted over a whole table would be
 * asserted over whatever earlier runs left behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcCashReceiptRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private CashReceiptRepository repository;
  @Autowired private OrganizationService organizations;
  @Autowired private TransferIdDeriver transferIds;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("workflow.cash_receipt is in the workflow schema, and its lifecycle may move")
  void livesInTheWorkflowSchema() {
    assertThat(privilege("workflow.cash_receipt", "INSERT")).isTrue();
    assertThat(privilege("workflow.cash_receipt", "SELECT")).isTrue();
    assertThat(privilege("workflow.cash_receipt", "UPDATE"))
        .as("workflow holds a status lifecycle, which moves in place (ADR-0011)")
        .isTrue();
  }

  @Test
  @DisplayName("the amount column is bigint cents, never numeric or floating point")
  void amountIsAnIntegerColumn() {
    String type =
        jdbc.sql(
                """
                SELECT data_type FROM information_schema.columns
                 WHERE table_schema = 'workflow' AND table_name = 'cash_receipt'
                   AND column_name = 'amount_minor_units'
                """)
            .query(String.class)
            .single();

    assertThat(type).isEqualTo("bigint");
  }

  @Test
  @DisplayName("a receipt round-trips, and is recorded only once for one key")
  void roundTripsAndIsRecordedOnce() {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();
    CashReceipt receipt = unconfirmed(organizationId, key, 250_000L);

    assertThat(repository.recordUnconfirmed(receipt)).isTrue();
    assertThat(repository.recordUnconfirmed(receipt))
        .as("a second attempt under the same key inserts nothing")
        .isFalse();

    Optional<CashReceipt> stored = repository.find(organizationId, key);
    assertThat(stored).isPresent();
    assertThat(stored.get().amount()).isEqualTo(Money.of(250_000L, CurrencyCode.MXN));
    assertThat(stored.get().valueDate()).isEqualTo(LocalDate.of(2026, 8, 18));
    assertThat(stored.get().bankReference()).isEqualTo(receipt.bankReference());
    assertThat(stored.get().operatorId()).isEqualTo("ops-marta");
    assertThat(stored.get().status()).isEqualTo(CashReceiptStatus.UNCONFIRMED);
    assertThat(stored.get().transferId()).isEqualTo(receipt.transferId());
  }

  @Test
  @DisplayName("a resolved receipt cannot be resolved a second time")
  void resolutionIsFinal() {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();
    CashReceipt receipt = unconfirmed(organizationId, key, 250_000L);
    repository.recordUnconfirmed(receipt);

    transactions.executeWithoutResult(
        status -> repository.resolve(withStatus(receipt, CashReceiptStatus.POSTED, null)));

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        repository.resolve(
                            withStatus(receipt, CashReceiptStatus.REJECTED, "ACCOUNT_NOT_FOUND"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(repository.find(organizationId, key))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.POSTED);
  }

  @Test
  @DisplayName("a claim held by another session fails immediately rather than waiting for it")
  void aHeldClaimFailsFast() throws SQLException {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();
    repository.recordUnconfirmed(unconfirmed(organizationId, key, 250_000L));

    // A second connection, holding the row exactly as a concurrent request would.
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

      assertThatThrownBy(
              () ->
                  transactions.executeWithoutResult(
                      status -> repository.claim(organizationId, key)))
          .isInstanceOf(DataAccessException.class)
          .satisfies(
              e ->
                  assertThat(sqlStateOf((Throwable) e))
                      .as("55P03 is lock_not_available — the state a NOWAIT claim reports")
                      .isEqualTo("55P03"));
      holder.rollback();
    }
  }

  @Test
  @DisplayName("the database refuses a zero or negative receipt")
  void nonPositiveAmountIsRefusedByTheDatabase() {
    OrganizationId organizationId = registerOrganization();
    String key = "key-" + UUID.randomUUID();

    assertThatThrownBy(
            () ->
                jdbc.sql(insertWithRawAmount())
                    .param("organizationId", organizationId.value())
                    .param("idempotencyKey", key)
                    .param("transferId", UUID.randomUUID())
                    .param("amountMinorUnits", -1L)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("one derived transfer id can never be claimed by two receipts")
  void transferIdIsUnique() {
    OrganizationId organizationId = registerOrganization();
    UUID transferId = UUID.randomUUID();
    jdbc.sql(insertWithRawAmount())
        .param("organizationId", organizationId.value())
        .param("idempotencyKey", "key-" + UUID.randomUUID())
        .param("transferId", transferId)
        .param("amountMinorUnits", 1_000L)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(insertWithRawAmount())
                    .param("organizationId", organizationId.value())
                    .param("idempotencyKey", "key-" + UUID.randomUUID())
                    .param("transferId", transferId)
                    .param("amountMinorUnits", 1_000L)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("a receipt cannot be recorded for an organization that is not registered")
  void requiresARegisteredOrganization() {
    assertThatThrownBy(
            () ->
                jdbc.sql(insertWithRawAmount())
                    .param("organizationId", UUID.randomUUID())
                    .param("idempotencyKey", "key-" + UUID.randomUUID())
                    .param("transferId", UUID.randomUUID())
                    .param("amountMinorUnits", 1_000L)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static String insertWithRawAmount() {
    return """
        INSERT INTO workflow.cash_receipt (
            organization_id, idempotency_key, transfer_id, amount_minor_units, currency,
            value_date, bank_reference, operator_id, request_fingerprint, status,
            rejection_reason, created_at, updated_at)
        VALUES (:organizationId, :idempotencyKey, :transferId, :amountMinorUnits, 'MXN',
                DATE '2026-08-18', 'SPEI-TEST', 'ops-marta', 'fingerprint', 'UNCONFIRMED',
                NULL, now(), now())
        """;
  }

  private OrganizationId registerOrganization() {
    return organizations
        .register(
            new RegisterOrganizationCommand(
                FreshTaxIdentifiers.fresh(),
                "Acme S.A. de C.V.",
                Optional.empty(),
                Optional.empty()))
        .organization()
        .organizationId();
  }

  private CashReceipt unconfirmed(OrganizationId organizationId, String key, long cents) {
    TransferId transferId = transferIds.derive(organizationId.toString(), key);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return new CashReceipt(
        organizationId,
        key,
        transferId,
        Money.of(cents, CurrencyCode.MXN),
        LocalDate.of(2026, 8, 18),
        "SPEI-2026-08-18-" + UUID.randomUUID(),
        "ops-marta",
        "fingerprint-" + UUID.randomUUID(),
        CashReceiptStatus.UNCONFIRMED,
        Optional.empty(),
        now,
        now);
  }

  private static CashReceipt withStatus(
      CashReceipt receipt, CashReceiptStatus status, String rejectionReason) {
    return new CashReceipt(
        receipt.organizationId(),
        receipt.idempotencyKey(),
        receipt.transferId(),
        receipt.amount(),
        receipt.valueDate(),
        receipt.bankReference(),
        receipt.operatorId(),
        receipt.requestFingerprint(),
        status,
        Optional.ofNullable(rejectionReason),
        receipt.createdAt(),
        Instant.now().truncatedTo(ChronoUnit.MILLIS));
  }

  private static String sqlStateOf(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return "";
  }

  private boolean privilege(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT has_table_privilege('fondaria_app', :table, :privilege)")
            .param("table", table)
            .param("privilege", privilege)
            .query(Boolean.class)
            .single());
  }
}
