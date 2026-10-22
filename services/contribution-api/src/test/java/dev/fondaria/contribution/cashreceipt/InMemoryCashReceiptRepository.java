package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.common.OrganizationId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link CashReceiptRepository} in a map, so the decisions {@link CashReceiptService} makes can be
 * proved without a database.
 *
 * <p>It records the <b>order</b> of every write, because the order is the thing being tested.
 * ADR-0011's rule — row first, ledger second, status third — is not visible in any single call's
 * result; it is only visible in the sequence, and a test that merely checked the end state would
 * pass just as happily on an implementation that posted the money first.
 */
final class InMemoryCashReceiptRepository implements CashReceiptRepository {

  private final Map<String, CashReceipt> receipts = new LinkedHashMap<>();
  private final List<String> writes = new ArrayList<>();
  private boolean contended;

  @Override
  public boolean recordUnconfirmed(CashReceipt receipt) {
    writes.add("recordUnconfirmed");
    return receipts.putIfAbsent(key(receipt.organizationId(), receipt.idempotencyKey()), receipt)
        == null;
  }

  @Override
  public Optional<CashReceipt> claim(OrganizationId organizationId, String idempotencyKey) {
    if (contended) {
      throw new org.springframework.dao.DataAccessResourceFailureException(
          "lock not available", new java.sql.SQLException("could not obtain lock on row", "55P03"));
    }
    return Optional.ofNullable(receipts.get(key(organizationId, idempotencyKey)));
  }

  @Override
  public CashReceipt resolve(CashReceipt receipt) {
    writes.add("resolve:" + receipt.status());
    receipts.put(key(receipt.organizationId(), receipt.idempotencyKey()), receipt);
    return receipt;
  }

  @Override
  public Optional<CashReceipt> find(OrganizationId organizationId, String idempotencyKey) {
    return Optional.ofNullable(receipts.get(key(organizationId, idempotencyKey)));
  }

  /** Makes every claim behave as PostgreSQL does when another session holds the row. */
  void contend() {
    contended = true;
  }

  /** Puts a receipt straight into storage, reconstructing a state a crash would have left. */
  void given(CashReceipt receipt) {
    receipts.put(key(receipt.organizationId(), receipt.idempotencyKey()), receipt);
  }

  List<String> writes() {
    return List.copyOf(writes);
  }

  private static String key(OrganizationId organizationId, String idempotencyKey) {
    return organizationId + ":" + idempotencyKey;
  }
}
