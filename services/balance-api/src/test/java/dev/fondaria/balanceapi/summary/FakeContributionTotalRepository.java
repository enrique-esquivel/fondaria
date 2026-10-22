package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ContributionTotalRepository} that returns a stated figure and records exactly what it
 * was asked for.
 *
 * <p>What it was asked for is the interesting half: the tenant id and the {@code
 * EMPLOYER_COLLECTIONS} account id must both be derived from the organization in the path and from
 * nothing else, and the period must be the calendar month of the reading clock in the platform's
 * zone. All three are assertions about arguments, not about a returned number.
 */
public final class FakeContributionTotalRepository implements ContributionTotalRepository {

  /** One call: what was asked for. */
  public record Ask(String tenantId, String employerCollectionsAccountId, StatementPeriod period) {}

  private final List<Ask> asks = new ArrayList<>();
  private long cents;

  public FakeContributionTotalRepository returning(long cents) {
    this.cents = cents;
    return this;
  }

  public List<Ask> asks() {
    return List.copyOf(asks);
  }

  @Override
  public Money contributionsIn(
      String tenantId, String employerCollectionsAccountId, StatementPeriod period) {
    asks.add(new Ask(tenantId, employerCollectionsAccountId, period));
    return Money.of(cents, CurrencyCode.MXN);
  }
}
