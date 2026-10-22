package dev.fondaria.contribution.cashreceipt;

import dev.fondaria.ledger.TransferId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A cache that works, and can then be told to stop — which is the only interesting thing about a
 * cache in this service. {@link #breakIt()} makes it behave as an unreachable Redis does: silent
 * misses and swallowed writes, never an exception reaching the caller.
 */
final class RecordingCashReceiptResponseCache implements CashReceiptResponseCache {

  private final Map<TransferId, CashReceipt> entries = new LinkedHashMap<>();
  private boolean broken;

  @Override
  public Optional<CashReceipt> find(TransferId transferId) {
    return broken ? Optional.empty() : Optional.ofNullable(entries.get(transferId));
  }

  @Override
  public void store(CashReceipt receipt) {
    if (!receipt.isResolved()) {
      throw new IllegalArgumentException("Only a resolved cash receipt may be cached");
    }
    if (!broken) {
      entries.put(receipt.transferId(), receipt);
    }
  }

  void breakIt() {
    broken = true;
  }

  int size() {
    return entries.size();
  }
}
