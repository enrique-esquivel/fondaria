package dev.fondaria.balanceapi.movement;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * An in-memory {@link MovementRepository} that answers exactly as the SQL does: the same filters,
 * the same ordering, and — importantly — the same {@code <=} cursor predicate, so the trimming
 * {@code MovementService} performs on top of it is exercised rather than assumed away.
 */
final class FakeMovementRepository implements MovementRepository {

  private final List<MovementLeg> legs = new ArrayList<>();

  private int pagesServed;

  FakeMovementRepository with(MovementLeg leg) {
    legs.add(leg);
    return this;
  }

  int pagesServed() {
    return pagesServed;
  }

  @Override
  public List<MovementLeg> page(
      String tenantId,
      List<String> accountIds,
      MovementQuery query,
      Optional<MovementCursor> cursor,
      int rowLimit) {
    pagesServed++;
    return legs.stream()
        .filter(leg -> accountIds.contains(leg.accountId()))
        .filter(leg -> query.from().map(from -> !leg.valueDate().isBefore(from)).orElse(true))
        .filter(leg -> query.to().map(to -> !leg.valueDate().isAfter(to)).orElse(true))
        .filter(leg -> query.transferCode().map(c -> c.ledgerCode() == leg.code()).orElse(true))
        .filter(leg -> cursor.map(position -> atOrBelow(leg, position)).orElse(true))
        .sorted(
            Comparator.comparing((MovementLeg leg) -> new BigInteger(leg.ledgerTimestamp()))
                .thenComparing(leg -> new BigInteger(leg.transferId()))
                .reversed()
                .thenComparing(leg -> leg.direction().leg()))
        .limit(rowLimit)
        .toList();
  }

  private static boolean atOrBelow(MovementLeg leg, MovementCursor position) {
    int byTimestamp = new BigInteger(leg.ledgerTimestamp()).compareTo(position.ledgerTimestamp());
    if (byTimestamp != 0) {
      return byTimestamp < 0;
    }
    return new BigInteger(leg.transferId()).compareTo(position.transferId()) <= 0;
  }
}
