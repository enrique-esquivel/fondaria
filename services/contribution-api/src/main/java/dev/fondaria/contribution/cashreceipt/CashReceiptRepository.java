package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.OrganizationId;
import java.util.Optional;

/**
 * Storage for {@code workflow.cash_receipt}.
 *
 * <p>The three write methods exist in this shape because ADR-0011's write order needs them to.
 * {@link #recordUnconfirmed} commits on its own, before the ledger is touched; {@link #claim} then
 * holds the row for the duration of the post, and {@link #resolve} closes it out. Collapsing them
 * into one call would either hold a transaction open before there is anything to hold, or lose the
 * committed {@code UNCONFIRMED} row that makes a crash mid-post reconcilable.
 *
 * <p>An interface for the same reason {@code OrganizationRepository} is one: the decisions worth
 * testing here belong to {@link CashReceiptService}, and should be provable without a database in
 * the loop. The one JDBC implementation proves the SQL separately against a real PostgreSQL.
 */
public interface CashReceiptRepository {

  /**
   * Commits {@code receipt} as {@link CashReceiptStatus#UNCONFIRMED} unless a receipt already
   * exists under its {@code (organizationId, idempotencyKey)}. Its own transaction: the row must be
   * durable <b>before</b> the ledger post, not at the end of one that also covers it.
   *
   * @return {@code true} if this call created the row, {@code false} if it already existed
   */
  boolean recordUnconfirmed(CashReceipt receipt);

  /**
   * Takes the receipt's claim and returns it, or reports that another request holds it. Must be
   * called inside a transaction; the claim is released when that transaction ends, including when
   * it ends by the process dying.
   *
   * <p>Reports contention by letting the driver's {@code CannotAcquireLockException} out, rather
   * than by returning an empty result that would be indistinguishable from "no such receipt".
   * {@link CashReceiptService} is what turns it into a 409, because that is where the derived
   * transfer id the caller needs to hear about is known.
   */
  Optional<CashReceipt> claim(OrganizationId organizationId, String idempotencyKey);

  /**
   * Moves a claimed receipt out of {@link CashReceiptStatus#UNCONFIRMED}, to {@code POSTED} or
   * {@code REJECTED}. Called inside the same transaction as {@link #claim}, so the row cannot be
   * resolved by two requests at once.
   */
  CashReceipt resolve(CashReceipt receipt);

  Optional<CashReceipt> find(OrganizationId organizationId, String idempotencyKey);
}
