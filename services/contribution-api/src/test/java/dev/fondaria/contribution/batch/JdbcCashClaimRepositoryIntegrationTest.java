package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.cashreceipt.CashReceipt;
import dev.fondaria.contribution.cashreceipt.CashReceiptRepository;
import dev.fondaria.contribution.cashreceipt.CashReceiptStatus;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import dev.fondaria.contribution.support.ServiceIntegrationTest;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL behind {@link CashClaimRepository}, against a real PostgreSQL. The constraint this suite
 * exists for is the primary key: <b>two batches can never consume the same wire</b>, enforced by
 * the database rather than by the service noticing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcCashClaimRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private CashClaimRepository claims;
  @Autowired private ContributionBatchRepository batches;
  @Autowired private CashReceiptRepository receipts;
  @Autowired private OrganizationService organizations;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("a posted, unclaimed receipt is a candidate; a claimed one is not")
  void claimingRemovesAReceiptFromTheCandidates() {
    OrganizationId organizationId = registerOrganization();
    TransferId receipt = givenPostedReceipt(organizationId, 750_000L);
    UUID batchId = givenBatch(organizationId, "claim.csv");

    assertThat(claims.unclaimed(organizationId))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(receipt);

    claims.claim(organizationId, batchId, List.of(receipt), Instant.now());

    assertThat(claims.unclaimed(organizationId)).isEmpty();
    assertThat(claims.claimedBy(batchId)).containsExactly(receipt);
  }

  @Test
  @DisplayName("an unconfirmed receipt is not a candidate")
  void onlyPostedReceiptsAreCandidates() {
    // A receipt left UNCONFIRMED by a crash mid-post is Task 3.3's convergence path.
    // Adopting it here would be this code writing another path's status lifecycle.
    OrganizationId organizationId = registerOrganization();
    givenUnconfirmedReceipt(organizationId, 750_000L);

    assertThat(claims.unclaimed(organizationId)).isEmpty();
  }

  @Test
  @DisplayName("a rejected receipt is not a candidate")
  void rejectedReceiptsAreNotCandidates() {
    OrganizationId organizationId = registerOrganization();
    CashReceipt receipt = givenUnconfirmedReceipt(organizationId, 750_000L);
    transactions.executeWithoutResult(
        status -> {
          receipts.claim(organizationId, receipt.idempotencyKey());
          receipts.resolve(withStatus(receipt, CashReceiptStatus.REJECTED, "INSUFFICIENT_FUNDS"));
        });

    assertThat(claims.unclaimed(organizationId)).isEmpty();
  }

  @Test
  @DisplayName("two batches can never consume the same wire")
  void oneReceiptIsClaimedAtMostOnce() {
    OrganizationId organizationId = registerOrganization();
    TransferId receipt = givenPostedReceipt(organizationId, 750_000L);
    UUID first = givenBatch(organizationId, "first.csv");
    UUID second = givenBatch(organizationId, "second.csv");
    claims.claim(organizationId, first, List.of(receipt), Instant.now());

    assertThatThrownBy(() -> claims.claim(organizationId, second, List.of(receipt), Instant.now()))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(claims.claimedBy(first)).containsExactly(receipt);
    assertThat(claims.claimedBy(second)).isEmpty();
  }

  @Test
  @DisplayName("a claim of several receipts is all or nothing")
  void aPartiallyContendedClaimTakesNothing() {
    // The rollback is what stops half of one batch's money staying attached to another.
    OrganizationId organizationId = registerOrganization();
    TransferId free = givenPostedReceipt(organizationId, 400_000L);
    TransferId taken = givenPostedReceipt(organizationId, 350_000L);
    UUID other = givenBatch(organizationId, "other.csv");
    UUID mine = givenBatch(organizationId, "mine.csv");
    claims.claim(organizationId, other, List.of(taken), Instant.now());

    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        claims.claim(organizationId, mine, List.of(free, taken), Instant.now())))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(claims.claimedBy(mine)).isEmpty();
    assertThat(claims.unclaimed(organizationId))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(free);
  }

  @Test
  @DisplayName("a claim can only reference a receipt that exists")
  void aClaimReferencesARealReceipt() {
    OrganizationId organizationId = registerOrganization();
    UUID batchId = givenBatch(organizationId, "phantom.csv");

    assertThatThrownBy(
            () ->
                claims.claim(
                    organizationId,
                    batchId,
                    List.of(TransferIdDeriver.toTransferId(UUID.randomUUID())),
                    Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("the candidate listing covers only this organization")
  void candidatesAreScopedToTheirTenant() {
    OrganizationId organizationId = registerOrganization();
    OrganizationId other = registerOrganization();
    TransferId receipt = givenPostedReceipt(organizationId, 750_000L);
    givenPostedReceipt(other, 750_000L);

    assertThat(claims.unclaimed(organizationId))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(receipt);
  }

  @Test
  @DisplayName("candidates come back oldest value date first")
  void candidatesAreOrderedByValueDate() {
    OrganizationId organizationId = registerOrganization();
    TransferId later = givenPostedReceipt(organizationId, 100L, LocalDate.of(2026, 8, 25));
    TransferId earlier = givenPostedReceipt(organizationId, 200L, LocalDate.of(2026, 8, 20));

    assertThat(claims.unclaimed(organizationId))
        .extracting(UnclaimedReceipt::transferId)
        .containsExactly(earlier, later);
  }

  private TransferId givenPostedReceipt(OrganizationId organizationId, long cents) {
    return givenPostedReceipt(organizationId, cents, LocalDate.of(2026, 8, 20));
  }

  private TransferId givenPostedReceipt(
      OrganizationId organizationId, long cents, LocalDate valueDate) {
    CashReceipt receipt = givenUnconfirmedReceipt(organizationId, cents, valueDate);
    transactions.executeWithoutResult(
        status -> {
          receipts.claim(organizationId, receipt.idempotencyKey());
          receipts.resolve(withStatus(receipt, CashReceiptStatus.POSTED, null));
        });
    return receipt.transferId();
  }

  private CashReceipt givenUnconfirmedReceipt(OrganizationId organizationId, long cents) {
    return givenUnconfirmedReceipt(organizationId, cents, LocalDate.of(2026, 8, 20));
  }

  /**
   * Written straight through {@code CashReceiptRepository} rather than through the endpoint: this
   * suite is about the claim table, and depending on the cash-receipt service's own behaviour would
   * make it fail for reasons that have nothing to do with claiming.
   */
  private CashReceipt givenUnconfirmedReceipt(
      OrganizationId organizationId, long cents, LocalDate valueDate) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    String key = "key-" + UUID.randomUUID();
    CashReceipt receipt =
        new CashReceipt(
            organizationId,
            key,
            TransferIdDeriver.toTransferId(UUID.randomUUID()),
            Money.of(cents, CurrencyCode.MXN),
            valueDate,
            "SPEI-" + UUID.randomUUID(),
            "ops-marta",
            "fingerprint-" + UUID.randomUUID(),
            CashReceiptStatus.UNCONFIRMED,
            Optional.empty(),
            now,
            now);
    receipts.recordUnconfirmed(receipt);
    return receipt;
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

  private UUID givenBatch(OrganizationId organizationId, String name) {
    String objectKey = ContributionFiles.objectKey(organizationId, name);
    String checksum = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return batches
        .submit(
            new ContributionBatch(
                ContributionBatchId.derive(organizationId, objectKey, checksum),
                organizationId,
                objectKey,
                checksum,
                ContributionBatchStatus.RECEIVED,
                ContributionFiles.workedExampleDeclaration(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                now,
                now))
        .batchId();
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
}
