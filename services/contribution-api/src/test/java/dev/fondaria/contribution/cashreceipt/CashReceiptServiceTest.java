package dev.fondaria.contribution.cashreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.common.TenantRef;
import dev.fondaria.contribution.config.IdentityProperties;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerRejectionReason;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The decisions {@link CashReceiptService} makes, proved without a database or a ledger container.
 *
 * <p>What this file is really about is <b>order and count</b>: that the PostgreSQL row exists
 * before the money moves, and that however many times a receipt is submitted, the ledger is asked
 * to move it exactly once. Both are invisible in an end state, which is why the ledger double
 * records its attempts and is given a hook to look at the world mid-post.
 */
class CashReceiptServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:15:30Z");
  private static final UUID LEDGER_NAMESPACE = UUID.randomUUID();
  private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);

  private InMemoryCashReceiptRepository receipts;
  private ScriptedLedgerPort ledger;
  private RecordingCashReceiptResponseCache cache;
  private StubOrganizationRepository organizationRepository;
  private AccountIdDeriver accountIds;
  private TransferIdDeriver transferIds;
  private CashReceiptService service;
  private OrganizationId organizationId;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    receipts = new InMemoryCashReceiptRepository();
    ledger = new ScriptedLedgerPort();
    cache = new RecordingCashReceiptResponseCache();
    organizationRepository = new StubOrganizationRepository();
    accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
    transferIds = new TransferIdDeriver(LEDGER_NAMESPACE);
    IdentityProperties identity = new IdentityProperties(UUID.randomUUID(), LEDGER_NAMESPACE);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    OrganizationService organizations =
        new OrganizationService(organizationRepository, ledger, accountIds, identity, clock);
    // Tracer.NOOP's current span is always a no-op, so TraceCorrelationPublisher returns
    // before ever touching a Redis connection — null is safe here, never dereferenced.
    meterRegistry = new SimpleMeterRegistry();
    CashReceiptTelemetry telemetry =
        new CashReceiptTelemetry(
            new TraceCorrelationPublisher(
                null, Tracer.NOOP, Duration.ofMinutes(5), new RedisMetrics(meterRegistry)),
            meterRegistry);
    service =
        new CashReceiptService(
            receipts,
            cache,
            organizations,
            ledger,
            accountIds,
            transferIds,
            identity,
            new TransactionTemplate(new DirectTransactionManager()),
            clock,
            telemetry);
    organizationId =
        organizationRepository.register(TaxIdentifier.of(TaxIdScheme.MX_RFC, "ABC010101XY1"));
  }

  @Test
  @DisplayName("the receipt row is committed as UNCONFIRMED before the money moves (ADR-0011)")
  void rowIsWrittenBeforeTheLedgerIsTouched() {
    List<CashReceiptStatus> statusWhenLedgerWasCalled = new ArrayList<>();
    ledger.observe(
        cmd ->
            receipts
                .find(organizationId, "key-1")
                .ifPresent(stored -> statusWhenLedgerWasCalled.add(stored.status())));

    service.record(command("key-1", 250_000L));

    assertThat(statusWhenLedgerWasCalled)
        .as("the ledger must not be asked to move money before a row records why")
        .containsExactly(CashReceiptStatus.UNCONFIRMED);
    assertThat(receipts.writes()).containsExactly("recordUnconfirmed", "resolve:POSTED");
  }

  @Test
  @DisplayName(
      "the transfer is BANK_CLEARING -> EMPLOYER_COLLECTIONS[org], carrying the bank reference")
  void postsTheChartOfAccountsMovement() {
    service.record(command("key-1", 250_000L));

    assertThat(ledger.attempts()).hasSize(1);
    CreateTransferCommand.Cash cash = (CreateTransferCommand.Cash) ledger.attempts().get(0);
    assertThat(cash.debitAccountId())
        .isEqualTo(accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING));
    assertThat(cash.creditAccountId())
        .isEqualTo(
            accountIds.derive(
                LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()));
    assertThat(cash.transferCode()).isEqualTo(LedgerCodes.TransferCode.CASH_RECEIPT);
    assertThat(cash.amount()).isEqualTo(Money.of(250_000L, CurrencyCode.MXN));
    assertThat(cash.externalReference())
        .as("the ledger carries the derivation of the bank reference, never its text")
        .isEqualTo(ExternalReference.derive(LEDGER_NAMESPACE, "SPEI-2026-08-18-0001"));
    assertThat(cash.tenant())
        .as("the movement is attributed to the organization that wired the money (ADR-0005)")
        .isEqualTo(TenantRef.derive(LEDGER_NAMESPACE, organizationId));
    assertThat(cash.id()).isEqualTo(transferIds.derive(organizationId.toString(), "key-1"));
  }

  @Test
  @DisplayName("resubmitting an identical receipt replays the original and moves money once")
  void resubmittingReplaysAndMovesOnce() {
    CashReceiptOutcome first = service.record(command("key-1", 250_000L));
    CashReceiptOutcome second = service.record(command("key-1", 250_000L));

    assertThat(first.disposition()).isEqualTo(CashReceiptOutcome.Disposition.POSTED);
    assertThat(second.disposition()).isEqualTo(CashReceiptOutcome.Disposition.REPLAYED);
    assertThat(second.receipt().transferId()).isEqualTo(first.receipt().transferId());
    assertThat(ledger.movements()).isEqualTo(1);
  }

  @Test
  @DisplayName("with the cache unavailable, a duplicate still results in exactly one movement")
  void cacheUnavailableStillMovesMoneyOnce() {
    service.record(command("key-1", 250_000L));
    cache.breakIt();

    CashReceiptOutcome second = service.record(command("key-1", 250_000L));

    assertThat(second.disposition()).isEqualTo(CashReceiptOutcome.Disposition.REPLAYED);
    assertThat(ledger.movements())
        .as("suppression is the derived transfer id, not the cache")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the same key with a different amount is refused, with the cache unavailable too")
  void sameKeyDifferentAmountIsRefusedWithoutTheCache() {
    service.record(command("key-1", 250_000L));
    cache.breakIt();
    int attemptsAfterTheOriginal = ledger.attempts().size();

    assertThatThrownBy(() -> service.record(command("key-1", 250_001L)))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(ledger.movements()).isEqualTo(1);
    assertThat(ledger.attempts())
        .as("a reused key is refused from the stored fingerprint, before the ledger is asked")
        .hasSize(attemptsAfterTheOriginal);
  }

  @Test
  @DisplayName("the same key for a different organization is a different receipt entirely")
  void sameKeyForAnotherOrganizationIsItsOwnReceipt() {
    OrganizationId other =
        organizationRepository.register(TaxIdentifier.of(TaxIdScheme.MX_RFC, "XYZ010101AB2"));

    service.record(command("shared-key", 250_000L));
    service.record(
        new RecordCashReceiptCommand(
            other,
            "shared-key",
            Money.of(250_000L, CurrencyCode.MXN),
            VALUE_DATE,
            "SPEI-2026-08-18-0002",
            "ops-marta"));

    assertThat(ledger.movements())
        .as("the key is scoped to the organization, so two employers cannot collide")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a concurrent duplicate is reported as in flight, not accepted")
  void aContendedClaimIsAConflict() {
    receipts.contend();

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(CashReceiptInFlightException.class);
    assertThat(ledger.movements()).isZero();
  }

  @Test
  @DisplayName("a ledger rejection leaves a REJECTED row and is cached as a business outcome")
  void aRejectionIsRecordedAndCached() {
    ledger.rejectWith(LedgerRejectionReason.ACCOUNT_NOT_FOUND);

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(CashReceiptRejectedException.class)
        .extracting(e -> ((CashReceiptRejectedException) e).reason())
        .isEqualTo("ACCOUNT_NOT_FOUND");
    assertThat(receipts.find(organizationId, "key-1"))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.REJECTED);
    assertThat(cache.size()).as("business rejections are cached, so a retry is cheap").isEqualTo(1);
  }

  @Test
  @DisplayName("an infrastructure failure leaves the row UNCONFIRMED and caches nothing")
  void anInfrastructureFailureIsNotCached() {
    ledger.failWith(() -> new IllegalStateException("ledger unreachable"));

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(CashReceiptUnconfirmedException.class);
    assertThat(receipts.find(organizationId, "key-1"))
        .get()
        .extracting(CashReceipt::status)
        .isEqualTo(CashReceiptStatus.UNCONFIRMED);
    assertThat(cache.size())
        .as("caching an outage would turn a blip into a permanent refusal")
        .isZero();
  }

  @Test
  @DisplayName("a crash before the ledger post is repaired by a retry, still one movement")
  void crashBeforeThePostConverges() {
    receipts.given(unconfirmedReceipt("key-1", 250_000L));

    CashReceiptOutcome outcome = service.record(command("key-1", 250_000L));

    assertThat(outcome.disposition()).isEqualTo(CashReceiptOutcome.Disposition.POSTED);
    assertThat(outcome.receipt().status()).isEqualTo(CashReceiptStatus.POSTED);
    assertThat(ledger.movements()).isEqualTo(1);
  }

  @Test
  @DisplayName("a crash after the ledger post is repaired by a retry, still one movement")
  void crashAfterThePostConverges() {
    // The exact state a crash between the two writes leaves: the money has moved and
    // the row still says UNCONFIRMED. This is the case ADR-0011's write order exists
    // to make recoverable, and the one a naive retry would double-credit.
    receipts.given(unconfirmedReceipt("key-1", 250_000L));
    ledger.givenPosted(
        CreateTransferCommand.Cash.single(
            transferIds.derive(organizationId.toString(), "key-1"),
            accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING),
            accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()),
            LedgerCodes.TransferCode.CASH_RECEIPT,
            Money.of(250_000L, CurrencyCode.MXN)));

    CashReceiptOutcome outcome = service.record(command("key-1", 250_000L));

    assertThat(outcome.disposition()).isEqualTo(CashReceiptOutcome.Disposition.REPLAYED);
    assertThat(outcome.receipt().status()).isEqualTo(CashReceiptStatus.POSTED);
    assertThat(ledger.movements()).isEqualTo(1);
  }

  @Test
  @DisplayName("a ledger movement that disagrees with the request is key reuse, not a replay")
  void aDisagreeingLedgerMovementIsKeyReuse() {
    TransferId transferId = transferIds.derive(organizationId.toString(), "key-1");
    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    receipts.given(unconfirmedReceipt("key-1", 250_000L));
    // The adapter collapses ExistsWithDifferentAmount into AlreadyExists, so the only way
    // to see a differing amount is to look the transfer up — docs/DESIGN-DECISIONS.md
    ledger.givenPostedAmount(
        transferId,
        accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING),
        collections,
        999_999L);

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(IdempotencyKeyReusedException.class);
  }

  @Test
  @DisplayName("a receipt for an unregistered organization writes nothing anywhere")
  void anUnknownOrganizationIsRefusedBeforeAnythingIsWritten() {
    OrganizationId unknown = new OrganizationId(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                service.record(
                    new RecordCashReceiptCommand(
                        unknown,
                        "key-1",
                        Money.of(250_000L, CurrencyCode.MXN),
                        VALUE_DATE,
                        "SPEI-2026-08-18-0001",
                        "ops-marta")))
        .isInstanceOf(OrganizationNotFoundException.class);
    assertThat(receipts.writes()).isEmpty();
    assertThat(ledger.movements()).isZero();
  }

  @Test
  @DisplayName("a new movement increments transfer_created_total, tagged with its transfer code")
  void postingIncrementsTransferCreated() {
    service.record(command("key-1", 250_000L));

    assertThat(
            meterRegistry
                .get("transfer_created_total")
                .tag("code", "CASH_RECEIPT")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a ledger rejection increments transfer_rejected_total, tagged with its reason")
  void rejectionIncrementsTransferRejected() {
    ledger.rejectWith(LedgerRejectionReason.ACCOUNT_NOT_FOUND);

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(CashReceiptRejectedException.class);

    assertThat(
            meterRegistry
                .get("transfer_rejected_total")
                .tag("reason", "ACCOUNT_NOT_FOUND")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a replayed submission increments idempotency_replay_total, not transfer_created")
  void replayIncrementsIdempotencyReplay() {
    service.record(command("key-1", 250_000L));

    service.record(command("key-1", 250_000L));

    assertThat(meterRegistry.get("idempotency_replay_total").counter().count()).isEqualTo(1.0);
    assertThat(
            meterRegistry
                .get("transfer_created_total")
                .tag("code", "CASH_RECEIPT")
                .counter()
                .count())
        .as("a replay never posts a second transfer")
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a reused key with a different amount increments idempotency_conflict_total")
  void keyReuseIncrementsIdempotencyConflict() {
    service.record(command("key-1", 250_000L));

    assertThatThrownBy(() -> service.record(command("key-1", 250_001L)))
        .isInstanceOf(IdempotencyKeyReusedException.class);

    assertThat(
            meterRegistry
                .get("idempotency_conflict_total")
                .tag("reason", "PAYLOAD_MISMATCH")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("a contended claim increments idempotency_conflict_total")
  void contendedClaimIncrementsIdempotencyConflict() {
    receipts.contend();

    assertThatThrownBy(() -> service.record(command("key-1", 250_000L)))
        .isInstanceOf(CashReceiptInFlightException.class);

    assertThat(
            meterRegistry
                .get("idempotency_conflict_total")
                .tag("reason", "CONCURRENT_CLAIM")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  private RecordCashReceiptCommand command(String idempotencyKey, long cents) {
    return new RecordCashReceiptCommand(
        organizationId,
        idempotencyKey,
        Money.of(cents, CurrencyCode.MXN),
        VALUE_DATE,
        "SPEI-2026-08-18-0001",
        "ops-marta");
  }

  private CashReceipt unconfirmedReceipt(String idempotencyKey, long cents) {
    RecordCashReceiptCommand command = command(idempotencyKey, cents);
    return new CashReceipt(
        organizationId,
        idempotencyKey,
        transferIds.derive(organizationId.toString(), idempotencyKey),
        command.amount(),
        command.valueDate(),
        command.bankReference(),
        command.operatorId(),
        CashReceiptFingerprint.of(command),
        CashReceiptStatus.UNCONFIRMED,
        Optional.empty(),
        NOW,
        NOW);
  }
}
