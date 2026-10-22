package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.TenantRef;
import dev.fondaria.contribution.config.IdentityProperties;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateTransferCommand;
import dev.fondaria.ledger.ExternalReference;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import dev.fondaria.ledger.TransferResult;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts the one movement Phase 3 makes: {@code BANK_CLEARING → EMPLOYER_COLLECTIONS[org]}, a {@code
 * CASH_RECEIPT} for a wire an operator has reconciled against the bank statement (ADR-0010,
 * chart-of-accounts.md §4).
 *
 * <h2>The write order, which is the whole design</h2>
 *
 * <ol>
 *   <li>the {@code workflow.cash_receipt} row is committed, status {@code UNCONFIRMED};
 *   <li>the transfer is posted through {@link LedgerPort};
 *   <li>the row moves to {@code POSTED} (or {@code REJECTED}).
 * </ol>
 *
 * <p>ADR-0011 requires exactly this. A crash after (1) leaves a row saying an operator asserted a
 * wire whose movement is unconfirmed — reconcilable, and repaired by any retry. The reverse order
 * would leave money moved with nothing recording why, which is the defect ADR-0011 exists to close.
 * Both writes are idempotent on keys derived from {@code (organizationId, idempotencyKey)}, so a
 * retry converges from any point in between rather than duplicating anything.
 *
 * <h2>Why nothing here reads the ledger before writing to it</h2>
 *
 * <p>There is no "does this transfer already exist" check. The transfer id is derived, so a
 * duplicate is refused by TigerBeetle itself, atomic with the write; asking first and then writing
 * would reintroduce exactly the read-then-write race the derivation removes (AGENTS.md §4). {@link
 * LedgerPort#lookupTransfer} is called only <i>after</i> the ledger has reported {@code
 * AlreadyExists}, to confirm the pre-existing movement is the one this request describes.
 *
 * <h2>What Redis does, and what it must never do</h2>
 *
 * <p>It caches a resolved receipt for 24 hours. Stop it and every acceptance criterion still holds:
 * duplicate suppression is the ledger's derived id, "same key, different request" is the
 * fingerprint stored in PostgreSQL, and a concurrent duplicate is a PostgreSQL row lock. Nothing in
 * this class asks Redis a question whose wrong answer could move money twice.
 */
@Service
public class CashReceiptService {

  private static final Logger LOGGER = LoggerFactory.getLogger(CashReceiptService.class);

  private final CashReceiptRepository repository;
  private final CashReceiptResponseCache cache;
  private final OrganizationService organizations;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final TransferIdDeriver transferIds;
  private final IdentityProperties identity;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final CashReceiptTelemetry telemetry;

  public CashReceiptService(
      CashReceiptRepository repository,
      CashReceiptResponseCache cache,
      OrganizationService organizations,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      TransferIdDeriver transferIds,
      IdentityProperties identity,
      TransactionTemplate transactions,
      Clock clock,
      CashReceiptTelemetry telemetry) {
    this.repository = repository;
    this.cache = cache;
    this.organizations = organizations;
    this.ledger = ledger;
    this.accountIds = accountIds;
    this.transferIds = transferIds;
    this.identity = identity;
    this.transactions = transactions;
    this.clock = clock;
    this.telemetry = telemetry;
    identity.requireComplete();
  }

  /**
   * Records the operator's assertion and posts its movement, converging on the same result however
   * many times it is called with the same {@code (organizationId, idempotencyKey)}.
   *
   * @throws dev.fondaria.contribution.organization.OrganizationNotFoundException no organization is
   *     registered under this id. Checked first, and it is a read: a receipt for an unregistered
   *     employer must never reach the point of opening an account for it (ADR-0011).
   * @throws IdempotencyKeyReusedException the key was used for a materially different request
   * @throws CashReceiptInFlightException another request holds this receipt's claim
   * @throws CashReceiptRejectedException the ledger refused the movement
   * @throws CashReceiptUnconfirmedException the ledger post did not complete
   */
  public CashReceiptOutcome record(RecordCashReceiptCommand command) {
    organizations.get(command.organizationId());

    TransferId transferId =
        transferIds.derive(command.organizationId().toString(), command.idempotencyKey());
    String fingerprint = CashReceiptFingerprint.of(command);

    Optional<CashReceipt> cached = cache.find(transferId);
    if (cached.isPresent()) {
      requireSameRequest(cached.get(), fingerprint, transferId);
      return completed(cached.get(), CashReceiptOutcome.Disposition.REPLAYED);
    }

    Instant now = clock.instant();
    repository.recordUnconfirmed(
        new CashReceipt(
            command.organizationId(),
            command.idempotencyKey(),
            transferId,
            command.amount(),
            command.valueDate(),
            command.bankReference(),
            command.operatorId(),
            fingerprint,
            CashReceiptStatus.UNCONFIRMED,
            Optional.empty(),
            now,
            now));

    // Whether this call inserted the row is deliberately not branched on. A row this
    // call did not insert is either a completed receipt to replay or one a crash left
    // UNCONFIRMED to repair, and the claim below distinguishes them by reading the
    // row's actual state rather than by inferring it from who won the insert.
    CashReceiptOutcome posted =
        transactions.execute(status -> post(command, transferId, fingerprint));

    // Outside the transaction, and in this order, on purpose. A rejection is an
    // outcome the row must keep, so it is cached and then raised — raising it from
    // inside the callback would roll back the very REJECTED status it reports.
    cache.store(posted.receipt());
    return completed(posted.receipt(), posted.disposition());
  }

  /**
   * The single place a resolved receipt becomes an answer. A {@code REJECTED} receipt is raised
   * rather than returned, so no caller can mistake a refused movement for a posted one, and it is
   * raised here — after the transaction has committed and the cache has been written — so the
   * rejection survives being reported.
   */
  private CashReceiptOutcome completed(
      CashReceipt receipt, CashReceiptOutcome.Disposition disposition) {
    if (disposition == CashReceiptOutcome.Disposition.REPLAYED) {
      telemetry.idempotencyReplay();
    }
    if (receipt.status() == CashReceiptStatus.REJECTED) {
      throw new CashReceiptRejectedException(receipt);
    }
    return new CashReceiptOutcome(receipt, disposition);
  }

  /**
   * Runs inside the claim's transaction: the row lock is held from {@link
   * CashReceiptRepository#claim} until this returns, so the ledger post and the status update
   * cannot interleave with another request for the same receipt.
   *
   * <p>Holding a database transaction across a ledger call is a deliberate trade, not an oversight.
   * It bounds the endpoint's concurrency to the connection pool for the duration of one ledger
   * round trip — acceptable for an ops-keyed reconciliation entry (ADR-0010 sizes it at roughly a
   * thousand a year) and worth it, because the alternative is a lease with a timeout, which invents
   * a window in which two requests both believe they hold the claim.
   */
  private CashReceiptOutcome post(
      RecordCashReceiptCommand command, TransferId transferId, String fingerprint) {
    CashReceipt claimed;
    try {
      claimed =
          repository
              .claim(command.organizationId(), command.idempotencyKey())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Cash receipt vanished between being recorded and being claimed"));
    } catch (DataAccessException e) {
      if (!isClaimContended(e)) {
        throw e;
      }
      telemetry.idempotencyConflict("CONCURRENT_CLAIM");
      throw new CashReceiptInFlightException(transferId, e);
    }

    requireSameRequest(claimed, fingerprint, transferId);
    if (claimed.isResolved()) {
      return new CashReceiptOutcome(claimed, CashReceiptOutcome.Disposition.REPLAYED);
    }

    TransferResult result = postToLedger(command, claimed, transferId);
    return switch (result) {
      case TransferResult.Created created -> {
        telemetry.transferCreated();
        LOGGER.info(
            "Cash receipt posted, transferId={}, organizationId={}",
            TransferIdDeriver.toUuid(transferId),
            command.organizationId());
        yield new CashReceiptOutcome(
            repository.resolve(withStatus(claimed, CashReceiptStatus.POSTED, Optional.empty())),
            CashReceiptOutcome.Disposition.POSTED);
      }
      case TransferResult.AlreadyExists exists -> {
        // The ledger already holds this movement — a crash after the post but before the
        // status update, or a request that raced this one. Confirm it is the movement this
        // request describes before adopting it: the adapter collapses every ExistsWith*
        // status into AlreadyExists, so a differing amount is invisible in the result.
        requireLedgerAgrees(command, transferId);
        LOGGER.info(
            "Cash receipt already existed in the ledger; converged, transferId={},"
                + " organizationId={}",
            TransferIdDeriver.toUuid(transferId),
            command.organizationId());
        yield new CashReceiptOutcome(
            repository.resolve(withStatus(claimed, CashReceiptStatus.POSTED, Optional.empty())),
            CashReceiptOutcome.Disposition.REPLAYED);
      }
      case TransferResult.Rejected rejected -> {
        telemetry.transferRejected(rejected.reason());
        LOGGER.warn(
            "Cash receipt refused by the ledger, transferId={}, organizationId={}, reason={}",
            TransferIdDeriver.toUuid(transferId),
            command.organizationId(),
            rejected.reason());
        yield new CashReceiptOutcome(
            repository.resolve(
                withStatus(
                    claimed, CashReceiptStatus.REJECTED, Optional.of(rejected.reason().name()))),
            CashReceiptOutcome.Disposition.POSTED);
      }
    };
  }

  private TransferResult postToLedger(
      RecordCashReceiptCommand command, CashReceipt claimed, TransferId transferId) {
    AccountId bankClearing = accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING);
    AccountId employerCollections =
        accountIds.derive(
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, command.organizationId().scope());
    CreateTransferCommand transfer =
        CreateTransferCommand.Cash.single(
                transferId,
                bankClearing,
                employerCollections,
                LedgerCodes.TransferCode.CASH_RECEIPT,
                claimed.amount(),
                // The bank reference itself stays in PostgreSQL; the ledger carries only its
                // derivation, so tie-out to a statement line works by recomputing it (ADR-0011).
                ExternalReference.derive(identity.ledgerNamespace(), claimed.bankReference()))
            // ADR-0005: the movement is attributed to the organization that wired the money.
            .withTenant(TenantRef.derive(identity.ledgerNamespace(), command.organizationId()));
    // Written before the post, not after: the correlation entry must exist no later than the
    // CDC event it is meant to link, and a post that never confirms is retried under the same
    // derived transferId anyway (Task 11.2).
    telemetry.correlateTrace(transferId);
    try {
      return ledger.createTransfer(transfer);
    } catch (RuntimeException e) {
      // The row stays UNCONFIRMED. This service does not know whether the post reached the
      // cluster, and guessing either way is worse than saying so: a retry converges, because
      // the transfer id is derived.
      LOGGER.error(
          "Cash receipt could not be posted; it remains unconfirmed, transferId={},"
              + " organizationId={}",
          TransferIdDeriver.toUuid(transferId),
          command.organizationId(),
          e);
      throw new CashReceiptUnconfirmedException(transferId, e);
    }
  }

  /**
   * The 422 of Task 3.3, answered from the stored fingerprint rather than from the ledger's result.
   * It has to be: {@code TigerBeetleLedgerAdapter} maps {@code ExistsWithDifferentAmount} to the
   * same {@code AlreadyExists} as an exact retry, so by the time the ledger has spoken the
   * difference is gone. Answering it from PostgreSQL also means it is answered with Redis stopped.
   */
  private void requireSameRequest(CashReceipt stored, String fingerprint, TransferId transferId) {
    if (!stored.requestFingerprint().equals(fingerprint)) {
      telemetry.idempotencyConflict("PAYLOAD_MISMATCH");
      // Neither fingerprint is logged: they are derived from the amount, the bank reference
      // and the operator, and a request's identity is not a thing to leave lying in a log.
      LOGGER.warn(
          "Idempotency key reused with a different request, transferId={}, organizationId={}",
          TransferIdDeriver.toUuid(transferId),
          stored.organizationId());
      throw new IdempotencyKeyReusedException(transferId);
    }
  }

  /**
   * Confirms a pre-existing ledger movement is the one this request describes. Belt and braces
   * behind the fingerprint check: the fingerprint proves two <i>requests</i> agree, and this proves
   * the request agrees with the <i>money</i> — which is what docs/DESIGN-DECISIONS.md means by
   * detecting an amount mismatch on lookup.
   */
  private void requireLedgerAgrees(RecordCashReceiptCommand command, TransferId transferId) {
    Optional<LedgerTransfer> existing = ledger.lookupTransfer(transferId);
    if (existing.isEmpty()) {
      // Reported as unconfirmed rather than posted: the ledger says the id is taken but
      // will not show it, so this service cannot claim the movement is what it asserted.
      throw new CashReceiptUnconfirmedException(
          transferId,
          new IllegalStateException("The ledger reported an existing transfer it cannot return"));
    }
    boolean agrees =
        existing.get() instanceof LedgerTransfer.Cash cash
            && cash.amount().equals(command.amount())
            && cash.transferCode() == LedgerCodes.TransferCode.CASH_RECEIPT
            && cash.creditAccountId()
                .equals(
                    accountIds.derive(
                        LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS,
                        command.organizationId().scope()));
    if (!agrees) {
      throw new IdempotencyKeyReusedException(transferId);
    }
  }

  /**
   * Whether a failed claim was contention rather than some other database failure, decided from the
   * driver's own {@code SQLSTATE} ({@code 55P03}, {@code lock_not_available}) rather than from
   * Spring's exception classification. Spring 6 no longer translates that state to {@code
   * CannotAcquireLockException} by default, and a 409 that quietly became a 500 on a framework
   * upgrade is not a failure this endpoint should be able to have.
   */
  private static boolean isClaimContended(DataAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private CashReceipt withStatus(
      CashReceipt receipt, CashReceiptStatus status, Optional<String> rejectionReason) {
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
        rejectionReason,
        receipt.createdAt(),
        clock.instant());
  }
}
