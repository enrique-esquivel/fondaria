package dev.fondaria.contribution.batch;

import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether a batch's declared total is covered by cash that has actually arrived, and if so
 * which receipts it consumes.
 *
 * <h2>Why an explicit match exists at all</h2>
 *
 * <p>{@code EMPLOYER_COLLECTIONS[org]} is one account per organization holding a running balance
 * across every batch that organization has ever sent (chart-of-accounts.md §2). It therefore cannot
 * answer "how much cash belongs to this batch" — and summing {@code workflow.cash_receipt} is
 * forbidden (ADR-0008 invariant 1). So the allocation is recorded explicitly, and the two stores
 * answer strictly different questions:
 *
 * <ul>
 *   <li><b>PostgreSQL says which receipts exist and which are already spoken for.</b> That is
 *       allocation state, the {@code workflow} schema's job, and TigerBeetle has no concept of it.
 *   <li><b>The ledger says how much, and whether it moved at all.</b> Every cent added up below
 *       comes from {@link LedgerPort#lookupTransfer}. Not one comes from a PostgreSQL column —
 *       {@code amount_minor_units} records what an operator <i>asserted</i> a wire was, which is a
 *       different fact from what happened.
 * </ul>
 *
 * <p>That split is also a free reconciliation check: a receipt whose transfer the ledger does not
 * hold, or holds against a different account or for a different amount, is not counted. Money that
 * did not move cannot fund a batch, however confidently a row says otherwise.
 *
 * <h2>The two matching rules, and why there is no third</h2>
 *
 * <ol>
 *   <li><b>Exactly one unclaimed receipt equals the declared total</b> — claim it. This is what
 *       lets files arrive out of order against separate wires: an employer with two outstanding
 *       wires and two files matches each to the right one by amount rather than by arrival order.
 *   <li><b>Otherwise, every unclaimed receipt together equals the declared total</b> — claim them
 *       all. This is one file funded by several wires, which is the ordinary way a large remittance
 *       arrives.
 *   <li><b>Otherwise the batch waits.</b>
 * </ol>
 *
 * <p>Neither rule can be ambiguous, which is the point. Rule 2 has exactly one candidate set by
 * construction. Rule 1 declines when two receipts both equal the total, rather than picking one —
 * choosing between them would be choosing whose money this is, and for money an ambiguous automatic
 * answer is worse than no answer. A general subset-sum search would find more matches and is
 * rejected for exactly that reason: several subsets can hit the same total, and nothing in the data
 * says which is right.
 *
 * <p>A batch that does not match is not rejected. The file is fine; the money is not here yet. It
 * rests in {@code PENDING_RECONCILIATION} and is re-evaluated on every sweep, so a late wire
 * resolves it with no further submission — and until then both halves show on the ageing report.
 *
 * <h2>How a human breaks a tie</h2>
 *
 * <p>Rules 1 and 2 decline rather than guess, so some batches can only be resolved by someone who
 * knows which wire is which. <b>Rule 0 below is what makes that possible:</b> receipts this batch
 * already holds are counted first, so assigning them — today by inserting rows into {@code
 * workflow.contribution_batch_cash_claim}, later through an operator endpoint — is enough on its
 * own. The next sweep sees the batch funded and validates it.
 *
 * <p>Without rule 0 that assignment would be worse than useless: the claimed receipts would
 * disappear from the unclaimed candidates, the gate would find nothing, and the batch would sit in
 * {@code PENDING_RECONCILIATION} forever with its money already spoken for.
 *
 * <p>An assignment that does <i>not</i> cover the declared total leaves the batch blocked, and the
 * gate deliberately does not top it up from whatever else is lying around. Mixing a human's partial
 * decision with automatic matching would consume wires nobody chose, which is the failure this
 * whole design refuses to have.
 */
@Component
public class ReconciliationGate {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReconciliationGate.class);

  private final CashClaimRepository claims;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;

  public ReconciliationGate(
      CashClaimRepository claims, LedgerPort ledger, AccountIdDeriver accountIds) {
    this.claims = claims;
    this.ledger = ledger;
    this.accountIds = accountIds;
  }

  /** What the gate concluded. Read-only: nothing here claims anything or writes a status. */
  public sealed interface Outcome {

    /** The declared total is covered exactly by these receipts, which this batch must claim. */
    record Matched(List<TransferId> receipts, long receivedCents) implements Outcome {}

    /**
     * Covered by receipts this batch <b>already holds</b> — validate it and claim nothing further.
     *
     * <p>Only reachable through human assignment: on the automatic path the claim and the status
     * change are one transaction, so a batch can never end up holding cash without being validated
     * by the same commit. This is the state an operator creates on purpose to break a tie the gate
     * refused to guess at.
     */
    record AlreadyFunded(List<TransferId> receipts, long receivedCents) implements Outcome {}

    /**
     * Not covered. {@code availableCents} is what this organization holds unclaimed <i>right
     * now</i>, read from the ledger — reported so an employer is told "we have 740,000 of the
     * 750,000 you declared" rather than merely "blocked".
     */
    record NotMatched(long declaredCents, long availableCents, int candidateCount)
        implements Outcome {}
  }

  /**
   * @param batchId the batch asking. Needed for rule 0 — a batch that already holds an assignment
   *     is answered from it, which is what makes a human's tie-break take effect.
   * @param declaredTotalCents the batch's declared grand total, in MXN cents, straight from the
   *     submission — the one number {@code contribution-file-v2.md} §5 says this gate reconciles
   */
  public Outcome evaluate(OrganizationId organizationId, UUID batchId, long declaredTotalCents) {
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());

    // Rule 0 — cash this batch already holds, which is how a human breaks a tie the rules
    // below refuse to guess at. Checked first and on its own: a batch holding an assignment
    // is answered from that assignment alone, never topped up from whatever else is
    // outstanding.
    List<TransferId> held = claims.claimedBy(batchId);
    if (!held.isEmpty()) {
      long heldCents = 0L;
      for (TransferId receipt : held) {
        Optional<Money> amount = confirm(receipt, employerCollections);
        if (amount.isPresent()) {
          heldCents = Math.addExact(heldCents, amount.get().cents());
        }
      }
      if (heldCents == declaredTotalCents) {
        return new Outcome.AlreadyFunded(List.copyOf(held), heldCents);
      }
      LOGGER.warn(
          "This batch holds {} cash receipt(s) that do not cover its declared total, so it stays"
              + " blocked rather than consuming wires nobody assigned to it, batchId={},"
              + " organizationId={}",
          held.size(),
          batchId,
          organizationId);
      return new Outcome.NotMatched(declaredTotalCents, heldCents, held.size());
    }

    List<TransferId> confirmedReceipts = new ArrayList<>();
    List<Long> confirmedAmounts = new ArrayList<>();
    long available = 0L;
    for (UnclaimedReceipt candidate : claims.unclaimed(organizationId)) {
      Optional<Money> amount = confirm(candidate.transferId(), employerCollections);
      if (amount.isEmpty()) {
        continue;
      }
      confirmedReceipts.add(candidate.transferId());
      confirmedAmounts.add(amount.get().cents());
      available = Math.addExact(available, amount.get().cents());
    }

    // Rule 1 — exactly one receipt for exactly the declared total.
    List<TransferId> exact = new ArrayList<>();
    for (int i = 0; i < confirmedReceipts.size(); i++) {
      if (confirmedAmounts.get(i) == declaredTotalCents) {
        exact.add(confirmedReceipts.get(i));
      }
    }
    if (exact.size() == 1) {
      return new Outcome.Matched(List.copyOf(exact), declaredTotalCents);
    }
    if (exact.size() > 1) {
      LOGGER.warn(
          "Reconciliation declined: {} unclaimed receipts each equal this batch's declared total,"
              + " so which one funds it is ambiguous, organizationId={}",
          exact.size(),
          organizationId);
      return new Outcome.NotMatched(declaredTotalCents, available, confirmedReceipts.size());
    }

    // Rule 2 — every unclaimed receipt together, and nothing in between.
    if (!confirmedReceipts.isEmpty() && available == declaredTotalCents) {
      return new Outcome.Matched(List.copyOf(confirmedReceipts), available);
    }

    logNotMatched(batchId, organizationId, confirmedReceipts.size());
    return new Outcome.NotMatched(declaredTotalCents, available, confirmedReceipts.size());
  }

  /**
   * The one log line a batch that stays pending was missing. Without it, a batch waiting on cash
   * that never arrives is indistinguishable from a sweep that stopped running at all — both look
   * like silence. No amount here, on purpose: {@code availableCents} is this organization's
   * unclaimed collections balance, and a balance is on the never-log list
   * (docs/ENGINEERING-RULES.md §7) — a count says just as much for diagnosis. DEBUG, not INFO: a
   * batch waiting on a wire can sit here for days, re-evaluated every sweep, and this is steady
   * state, not an event.
   */
  private void logNotMatched(UUID batchId, OrganizationId organizationId, int candidateCount) {
    LOGGER.debug(
        "Contribution batch not reconciled this sweep, batchId={}, organizationId={},"
            + " candidateReceipts={}",
        batchId,
        organizationId,
        candidateCount);
  }

  /**
   * Confirms one receipt against the ledger and returns what actually moved.
   *
   * <p>Three things have to hold, not one: the ledger holds the transfer, it is a {@code
   * CASH_RECEIPT}, and it credited <i>this</i> organization's collections account. Checking only
   * that the id exists would let a transfer belonging to some other movement fund a batch, and the
   * amount is taken from the ledger rather than compared to PostgreSQL's because there is nothing
   * to compare it to — the ledger's number is the only one that counts.
   *
   * @return empty when the ledger does not confirm it, which quietly excludes it from this batch's
   *     funding and leaves it visible on the ageing report as cash nothing has claimed
   */
  private Optional<Money> confirm(TransferId transferId, AccountId employerCollections) {
    Optional<LedgerTransfer> transfer = ledger.lookupTransfer(transferId);
    if (transfer.isEmpty()) {
      LOGGER.warn(
          "A cash receipt is recorded as posted but the ledger does not hold its transfer;"
              + " it funds no batch, transferId={}",
          TransferIdDeriver.toUuid(transferId));
      return Optional.empty();
    }
    if (!(transfer.get() instanceof LedgerTransfer.Cash cash)
        || cash.transferCode() != LedgerCodes.TransferCode.CASH_RECEIPT
        || !cash.creditAccountId().equals(employerCollections)) {
      LOGGER.warn(
          "A cash receipt's transfer is not a CASH_RECEIPT into this organization's collections;"
              + " it funds no batch, transferId={}",
          TransferIdDeriver.toUuid(transferId));
      return Optional.empty();
    }
    return Optional.of(cash.amount());
  }
}
