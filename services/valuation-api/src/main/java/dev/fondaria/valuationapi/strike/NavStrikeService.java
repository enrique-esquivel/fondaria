package dev.fondaria.valuationapi.strike;

import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.NavCache;
import dev.fondaria.valuation.NavPerUnit;
import dev.fondaria.valuation.ReportedBalanceSource;
import dev.fondaria.valuationapi.fund.FundDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Divides what this service accepted a provider reported (Task 20.3) by units in issue read from
 * the ledger, and records the result (Task 20.4).
 *
 * <p><b>The arithmetic is {@link NavPerUnit#strike}, unchanged from Task 20.2.</b> This class only
 * supplies its three inputs — the fund's declared opening price ({@link FundDirectory}), the
 * accepted valuation ({@link ReportedBalanceSource}), and units in issue read through {@link
 * LedgerPort#lookupAccount}, never summed from PostgreSQL (ADR-0008) — and persists what it struck.
 *
 * <p>Deliberately does not reuse {@code ReportedBalanceValuationSource}: that class depends on the
 * wider {@code FundRepository}, whose {@code publish} this service must never be able to call (see
 * {@link FundDirectory}'s javadoc). The lookup this class repeats instead is six lines, not the
 * division itself.
 *
 * <p>Idempotent on {@code (fundId, valuationDate)}, delegated to {@link FundNavRepository#strike}:
 * a second call for a date already struck returns the original row — units in issue as it read them
 * the first time, never recomputed against whatever the ledger says now.
 */
@Service
public class NavStrikeService {

  private static final String SOURCE = "MODEL_B";

  private final FundDirectory funds;
  private final ReportedBalanceSource reportedBalances;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIdDeriver;
  private final FundNavRepository fundNavRepository;
  private final NavCache navCache;
  private final Clock clock;

  public NavStrikeService(
      FundDirectory funds,
      ReportedBalanceSource reportedBalances,
      LedgerPort ledger,
      AccountIdDeriver accountIdDeriver,
      FundNavRepository fundNavRepository,
      NavCache navCache,
      Clock clock) {
    this.funds = Objects.requireNonNull(funds, "funds must not be null");
    this.reportedBalances =
        Objects.requireNonNull(reportedBalances, "reportedBalances must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.accountIdDeriver =
        Objects.requireNonNull(accountIdDeriver, "accountIdDeriver must not be null");
    this.fundNavRepository =
        Objects.requireNonNull(fundNavRepository, "fundNavRepository must not be null");
    this.navCache = Objects.requireNonNull(navCache, "navCache must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * @throws NoSuchElementException no valuation has been accepted for this fund and date (Task
   *     20.3) — there is nothing to strike from
   * @throws IllegalArgumentException the fund is not registered as of {@code valuationDate} (Task
   *     20.1)
   */
  public FundNav strike(FundId fundId, LocalDate valuationDate) {
    Objects.requireNonNull(fundId, "fundId must not be null");
    Objects.requireNonNull(valuationDate, "valuationDate must not be null");

    Fund fund =
        funds
            .resolveAsOf(fundId, valuationDate)
            .orElseThrow(() -> new IllegalArgumentException("Fund not found: " + fundId));
    Money reportedValue = reportedBalances.reportedValueFor(fundId, valuationDate);
    Units unitsInIssue = unitsInIssue(fundId);
    NavPerUnit navPerUnit = NavPerUnit.strike(reportedValue, unitsInIssue, fund.openingNav());

    FundNav candidate =
        new FundNav(
            0L,
            fundId,
            valuationDate,
            navPerUnit.nav(),
            navPerUnit.unitsInIssue(),
            SOURCE,
            null,
            null,
            clock.instant());
    FundNav struck = fundNavRepository.strike(candidate);

    // Publication (Task 20.6). This instance picks the price up now; every other instance
    // does so on its next sweep, which is what "within the documented interval" bounds.
    // After the write, never before: a cache refreshed from a strike that then failed would
    // be serving a price the store never accepted. Refreshing from the repository rather
    // than holding `struck` directly also makes the idempotent case correct on its own --
    // a second strike for a date already struck returns the original, and that is what the
    // cache should end up holding.
    navCache.refresh(fundId);
    return struck;
  }

  private Units unitsInIssue(FundId fundId) {
    AccountId accountId =
        accountIdDeriver.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
    return ledger
        .lookupAccount(accountId)
        .map(account -> ((LedgerAccount.Unit) account).balance())
        .orElseGet(() -> Units.of(0));
  }
}
