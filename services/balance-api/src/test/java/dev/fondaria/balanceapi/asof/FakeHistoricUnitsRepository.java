package dev.fondaria.balanceapi.asof;

import dev.fondaria.common.Units;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * An in-memory {@code projection.participant_units}, holding whole change series per account and
 * resolving the same "latest at or before" rule the SQL does.
 *
 * <p>The rule is reimplemented rather than stubbed on purpose: a fake that simply returned a
 * prepared answer could not tell a gap date apart from an activity date, which is half of what Task
 * 6.4 has to get right. The other half — that the <i>index</i> can serve that rule at scale — is
 * only provable against a real database, and lives in {@code AsOfBalanceIntegrationTest}.
 */
final class FakeHistoricUnitsRepository implements HistoricUnitsRepository {

  private final Map<String, TreeMap<LocalDate, Long>> series = new java.util.LinkedHashMap<>();
  private final List<String> accountsAsked = new ArrayList<>();

  /** Records that {@code accountId} held {@code unitsScaled} from {@code effectiveDate} onward. */
  FakeHistoricUnitsRepository holding(String accountId, LocalDate effectiveDate, long unitsScaled) {
    series.computeIfAbsent(accountId, key -> new TreeMap<>()).put(effectiveDate, unitsScaled);
    return this;
  }

  /** Every account id the service asked about on the last call. */
  List<String> accountsAsked() {
    return List.copyOf(accountsAsked);
  }

  @Override
  public List<HistoricHolding> holdingsAsOf(
      String tenantId, List<HoldingAccountKey> accounts, LocalDate asOfDate) {
    accountsAsked.clear();
    Set<String> seen = new HashSet<>();
    List<HistoricHolding> found = new ArrayList<>();
    for (HoldingAccountKey account : accounts) {
      accountsAsked.add(account.accountId());
      if (!seen.add(account.accountId())) {
        throw new IllegalStateException("the same account was asked for twice: " + account);
      }
      Optional.ofNullable(series.get(account.accountId()))
          .map(byDate -> byDate.floorEntry(asOfDate))
          .ifPresent(
              entry ->
                  found.add(
                      new HistoricHolding(account, entry.getKey(), Units.of(entry.getValue()))));
    }
    // The real statement is a UNION with no outer ordering; shuffling deterministically here keeps
    // the service from depending on an order the database does not promise.
    found.sort(Comparator.comparing((HistoricHolding h) -> h.account().accountId()).reversed());
    return found;
  }
}
