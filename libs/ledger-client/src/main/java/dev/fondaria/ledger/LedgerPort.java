package dev.fondaria.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The one way service code touches the ledger. There is exactly one implementation — TigerBeetle,
 * Task 2.4 (ADR-0008) — and it speaks only in the domain types of this package, never a vendor
 * type, and passes the conformance suite (Task 2.6).
 *
 * <p>An implementation is a <b>thin translator</b>: it maps its engine's own results into {@link
 * TransferResult}, and never computes whether a balance suffices. Balance floors, transfer-id
 * idempotency, linked-chain atomicity and the pending lifecycle are enforced inside the engine,
 * atomic with the write — so only an engine with native ledger semantics may back this port.
 * PostgreSQL is not such an engine: it is the CDC-fed projection (Phases 4→5→6), which holds
 * history and never enforces a ledger rule.
 *
 * <p>Never compute a participant balance by summing rows in PostgreSQL; always read it through
 * {@link #lookupAccount} or {@link #lookupAccounts}.
 */
public interface LedgerPort {

  AccountId createAccount(CreateAccountCommand cmd);

  /**
   * Opens every account in {@code cmds}, with exactly {@link #createAccount}'s per-account
   * semantics: an account that already exists is not an error, and any other refusal throws. The
   * result mirrors {@code cmds} position for position.
   *
   * <p><b>Not atomic.</b> Unlike {@link #createLinkedChain}, each account stands alone; a refusal
   * part-way through can leave the accounts before it created. That is safe for the same reason
   * {@link #createAccount} is safe to repeat: an account id is derived, so a rerun of the whole
   * list re-opens nothing and creates only what is still missing.
   *
   * <p>Exists for throughput, not semantics (row 26p, {@code TASK-LOG.md} step 112): the ledger
   * serves a bounded number of requests per second whatever each one carries, so opening a batch's
   * ~8,000 holdings one request at a time cost seconds where one request costs milliseconds. An
   * implementation should send as few requests as the ledger's request size allows ({@link
   * LedgerLimits#MAX_ACCOUNTS_PER_REQUEST}); the default below is the one-at-a-time loop, correct
   * but slow, so a test double gets the semantics for free.
   */
  default List<AccountId> createAccounts(List<CreateAccountCommand> cmds) {
    return cmds.stream().map(this::createAccount).toList();
  }

  Optional<LedgerAccount> lookupAccount(AccountId id);

  List<LedgerAccount> lookupAccounts(List<AccountId> ids);

  TransferResult createTransfer(CreateTransferCommand cmd);

  List<TransferResult> createTransfers(List<CreateTransferCommand> cmds);

  /**
   * All-or-nothing: either every transfer in the chain is created, or none is. The result list
   * mirrors {@code cmds} position-for-position, so the failing element is identified by its index
   * (and its {@link TransferResult.Rejected#reason()}).
   */
  List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds);

  Optional<LedgerTransfer> lookupTransfer(TransferId id);

  List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query);

  /**
   * The timestamp of the most recent transfer the ledger has committed, on any account, or empty
   * when it has committed none — or when this implementation cannot tell.
   *
   * <p>A <b>progress marker</b>, not a balance and not a watermark anything may be summed up to: it
   * exists so a reader of the CDC-fed projection can tell, cheaply, that the projection has not yet
   * received everything the ledger holds (ADR-0038). Empty means "unknown", so a caller must treat
   * it as "cannot tell" and fall back to whatever it did before it had this; the default below is
   * that answer, so a test double gets it for free.
   */
  default Optional<Instant> latestTransferTimestamp() {
    return Optional.empty();
  }
}
