package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Storage for {@code workflow.contribution_batch_cash_claim} — which cash receipts a batch has
 * consumed.
 *
 * <p>This is the one thing PostgreSQL knows about an employer's money that the ledger cannot:
 * TigerBeetle has no concept of a wire being spoken for by a batch. What it must never become is a
 * source of amounts. No method here returns one, and none ever should — every cent the
 * reconciliation gate adds up comes from {@code LedgerPort.lookupTransfer} (ADR-0008 invariant 1).
 */
public interface CashClaimRepository {

  /**
   * This organization's {@code POSTED} cash receipts that no batch has claimed, oldest value date
   * first.
   *
   * <p>Only {@code POSTED} ones. A receipt left {@code UNCONFIRMED} by a crash mid-post is Task
   * 3.3's convergence path — a retry of the receipt call resolves it — and adopting it here would
   * be this code writing another path's status lifecycle. Until it converges the file waits, and
   * the ageing report shows both halves, which is exactly the state that should be visible.
   */
  List<UnclaimedReceipt> unclaimed(OrganizationId organizationId);

  /**
   * Claims every listed receipt for {@code batchId}, all or nothing.
   *
   * <p>The primary key of {@code contribution_batch_cash_claim} is what enforces "one receipt, one
   * batch", so a receipt taken by another batch in the meantime fails this call and rolls the whole
   * claim back — never half of one batch's money left attached to another's.
   *
   * @throws org.springframework.dao.DuplicateKeyException a listed receipt is already claimed
   */
  void claim(
      OrganizationId organizationId, UUID batchId, List<TransferId> transferIds, Instant now);

  /** The receipts a batch has claimed. */
  List<TransferId> claimedBy(UUID batchId);
}
