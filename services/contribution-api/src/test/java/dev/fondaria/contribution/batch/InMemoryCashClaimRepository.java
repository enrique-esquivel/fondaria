package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.ledger.TransferId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * In-memory {@link CashClaimRepository}.
 *
 * <p>The one behaviour it must not simplify is the primary key: a receipt already claimed makes the
 * whole claim fail, and nothing partial is left behind. That is what stops two batches consuming
 * the same wire, so a double that let the second claim through would let a gate with the same bug
 * pass. Everything is rolled back on conflict, exactly as the real transaction does.
 */
final class InMemoryCashClaimRepository implements CashClaimRepository {

  private final Map<TransferId, UnclaimedReceipt> receipts = new LinkedHashMap<>();
  private final Map<TransferId, UUID> claimedBy = new LinkedHashMap<>();

  /** Records a receipt as {@code POSTED} and unclaimed, as Task 3.3's endpoint leaves one. */
  void givenPostedReceipt(TransferId transferId, LocalDate valueDate, Instant postedAt) {
    receipts.put(transferId, new UnclaimedReceipt(transferId, valueDate, postedAt));
  }

  @Override
  public List<UnclaimedReceipt> unclaimed(OrganizationId organizationId) {
    return receipts.values().stream()
        .filter(receipt -> !claimedBy.containsKey(receipt.transferId()))
        .sorted(
            Comparator.comparing(UnclaimedReceipt::valueDate)
                .thenComparing(UnclaimedReceipt::postedAt))
        .toList();
  }

  @Override
  public void claim(
      OrganizationId organizationId, UUID batchId, List<TransferId> transferIds, Instant now) {
    if (transferIds.isEmpty()) {
      throw new IllegalArgumentException("A batch claims at least one receipt or none at all");
    }
    Set<TransferId> taken = new LinkedHashSet<>();
    for (TransferId transferId : transferIds) {
      if (claimedBy.putIfAbsent(transferId, batchId) != null) {
        taken.forEach(claimedBy::remove);
        throw new DuplicateKeyException("Receipt already claimed by another batch");
      }
      taken.add(transferId);
    }
  }

  @Override
  public List<TransferId> claimedBy(UUID batchId) {
    List<TransferId> claimed = new ArrayList<>();
    claimedBy.forEach(
        (transferId, owner) -> {
          if (owner.equals(batchId)) {
            claimed.add(transferId);
          }
        });
    return claimed;
  }

  /** Pins a receipt to another batch, as a concurrent claim would. */
  void givenClaimedBy(TransferId transferId, UUID batchId) {
    claimedBy.put(transferId, batchId);
  }
}
